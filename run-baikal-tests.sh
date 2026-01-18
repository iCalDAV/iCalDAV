#!/bin/bash
#
# Run CalDAV integration tests against a Baikal server.
#
# Baikal (sabre/dav based) requires initial setup through web wizard.
# This script automates the setup process before running tests.
#
# Usage:
#   ./run-baikal-tests.sh           # Run all integration tests
#   ./run-baikal-tests.sh --keep    # Keep Baikal running after tests
#   ./run-baikal-tests.sh --skip-setup  # Skip Docker setup (use existing)
#

set -e

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$SCRIPT_DIR"

# Configuration
BAIKAL_URL="http://localhost:8081"
BAIKAL_USER="testuser"
BAIKAL_PASS="testpass123"
BAIKAL_ADMIN_PASS="adminpass123"
COMPOSE_FILE="docker-compose.baikal.yml"
MAX_WAIT_SECONDS=90

# Parse arguments
KEEP_RUNNING=false
SKIP_SETUP=false

for arg in "$@"; do
    case $arg in
        --keep)
            KEEP_RUNNING=true
            ;;
        --skip-setup)
            SKIP_SETUP=true
            ;;
        --help)
            echo "Usage: $0 [--keep] [--skip-setup]"
            echo "  --keep        Keep Baikal running after tests"
            echo "  --skip-setup  Skip Docker setup (use existing Baikal)"
            exit 0
            ;;
    esac
done

# Colors for output
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
NC='\033[0m' # No Color

log_info() {
    echo -e "${GREEN}[INFO]${NC} $1"
}

log_warn() {
    echo -e "${YELLOW}[WARN]${NC} $1"
}

log_error() {
    echo -e "${RED}[ERROR]${NC} $1"
}

cleanup() {
    if [ "$KEEP_RUNNING" = false ] && [ "$SKIP_SETUP" = false ] && [ -n "$DOCKER_COMPOSE" ]; then
        log_info "Stopping Baikal..."
        $DOCKER_COMPOSE -f "$COMPOSE_FILE" down -v 2>/dev/null || true
    fi
}

# Set up cleanup trap
trap cleanup EXIT

# Check Docker
if ! command -v docker &> /dev/null; then
    log_error "Docker is not installed. Please install Docker first."
    exit 1
fi

# Detect docker-compose command (v1 vs v2)
if command -v docker-compose &> /dev/null; then
    DOCKER_COMPOSE="docker-compose"
elif docker compose version &> /dev/null; then
    DOCKER_COMPOSE="docker compose"
else
    log_error "docker-compose is not installed. Please install docker-compose first."
    exit 1
fi

log_info "Using: $DOCKER_COMPOSE"

# Start Baikal
if [ "$SKIP_SETUP" = false ]; then
    log_info "Starting Baikal..."
    $DOCKER_COMPOSE -f "$COMPOSE_FILE" down -v 2>/dev/null || true
    $DOCKER_COMPOSE -f "$COMPOSE_FILE" up -d

    # Wait for Baikal to be ready
    log_info "Waiting for Baikal to be ready (max ${MAX_WAIT_SECONDS}s)..."

    elapsed=0
    while [ $elapsed -lt $MAX_WAIT_SECONDS ]; do
        HTTP_CODE=$(curl -s -o /dev/null -w "%{http_code}" "$BAIKAL_URL/" 2>/dev/null || echo "000")
        if [ "$HTTP_CODE" = "200" ] || [ "$HTTP_CODE" = "302" ]; then
            log_info "Baikal is ready!"
            break
        fi

        echo -n "."
        sleep 2
        elapsed=$((elapsed + 2))
    done
    echo ""

    if [ $elapsed -ge $MAX_WAIT_SECONDS ]; then
        log_error "Baikal did not become ready within ${MAX_WAIT_SECONDS} seconds"
        $DOCKER_COMPOSE -f "$COMPOSE_FILE" logs baikal
        exit 1
    fi

    # Complete Baikal setup wizard (requires CSRF token handling)
    log_info "Completing Baikal setup wizard..."
    COOKIES=$(mktemp)

    # Step 1: Get install page and extract CSRF token
    INSTALL_PAGE=$(curl -s -c "$COOKIES" -b "$COOKIES" "$BAIKAL_URL/admin/install/")
    CSRF_TOKEN=$(echo "$INSTALL_PAGE" | grep -o 'CSRF_TOKEN" value="[^"]*' | head -1 | cut -d'"' -f3)

    if [ -z "$CSRF_TOKEN" ]; then
        log_warn "Could not extract CSRF token, setup may already be complete"
    else
        log_info "Got CSRF token: ${CSRF_TOKEN:0:8}..."

        # Submit initial setup form
        SETUP_RESPONSE=$(curl -s -o /dev/null -w "%{http_code}" \
            -c "$COOKIES" -b "$COOKIES" \
            -X POST \
            -d "Baikal_Model_Config_Standard::submitted=1&refreshed=0&CSRF_TOKEN=$CSRF_TOKEN&data[timezone]=UTC&witness[timezone]=1&data[card_enabled]=1&witness[card_enabled]=1&data[cal_enabled]=1&witness[cal_enabled]=1&data[invite_from]=noreply@localhost&witness[invite_from]=1&data[dav_auth_type]=Basic&witness[dav_auth_type]=1&data[admin_passwordhash]=$BAIKAL_ADMIN_PASS&witness[admin_passwordhash]=1&data[admin_passwordhash_confirm]=$BAIKAL_ADMIN_PASS&witness[admin_passwordhash_confirm]=1" \
            "$BAIKAL_URL/admin/install/" 2>/dev/null || echo "000")

        if [ "$SETUP_RESPONSE" = "302" ] || [ "$SETUP_RESPONSE" = "200" ]; then
            log_info "Initial setup completed (HTTP $SETUP_RESPONSE)"
        else
            log_warn "Initial setup returned HTTP $SETUP_RESPONSE"
        fi
    fi

    # Step 2: Database setup page
    sleep 1
    DB_PAGE=$(curl -s -c "$COOKIES" -b "$COOKIES" "$BAIKAL_URL/admin/install/?/database")
    DB_CSRF=$(echo "$DB_PAGE" | grep -o 'CSRF_TOKEN" value="[^"]*' | head -1 | cut -d'"' -f3)

    if [ -n "$DB_CSRF" ]; then
        log_info "Configuring database..."
        DB_RESPONSE=$(curl -s -o /dev/null -w "%{http_code}" \
            -c "$COOKIES" -b "$COOKIES" \
            -X POST \
            -d "Baikal_Model_Config_Database::submitted=1&refreshed=0&CSRF_TOKEN=$DB_CSRF&data[backend]=sqlite&witness[backend]=1&data[sqlite_file]=/var/www/baikal/Specific/db/db.sqlite&witness[sqlite_file]=1" \
            "$BAIKAL_URL/admin/install/?/database" 2>/dev/null || echo "000")

        if [ "$DB_RESPONSE" = "302" ] || [ "$DB_RESPONSE" = "200" ]; then
            log_info "Database setup completed (HTTP $DB_RESPONSE)"
        else
            log_warn "Database setup returned HTTP $DB_RESPONSE"
        fi
    fi

    # Wait for setup to complete
    sleep 2

    # Step 3: Login to admin panel
    log_info "Logging into admin panel..."
    LOGIN_RESPONSE=$(curl -s -o /dev/null -w "%{http_code}" \
        -c "$COOKIES" -b "$COOKIES" \
        -X POST \
        -d "auth=1&login=admin&password=$BAIKAL_ADMIN_PASS" \
        "$BAIKAL_URL/admin/" 2>/dev/null || echo "000")

    if [ "$LOGIN_RESPONSE" = "302" ] || [ "$LOGIN_RESPONSE" = "200" ]; then
        log_info "Admin login successful (HTTP $LOGIN_RESPONSE)"
    else
        log_warn "Admin login returned HTTP $LOGIN_RESPONSE"
    fi

    # Step 4: Create test user
    log_info "Creating test user..."
    sleep 1
    USER_PAGE=$(curl -s -c "$COOKIES" -b "$COOKIES" "$BAIKAL_URL/admin/?/users/new/1/")
    USER_CSRF=$(echo "$USER_PAGE" | grep -o 'CSRF_TOKEN" value="[^"]*' | head -1 | cut -d'"' -f3)

    if [ -n "$USER_CSRF" ]; then
        USER_RESPONSE=$(curl -s -o /dev/null -w "%{http_code}" \
            -c "$COOKIES" -b "$COOKIES" \
            -X POST \
            -d "Baikal_Model_User::submitted=1&refreshed=0&CSRF_TOKEN=$USER_CSRF&data[username]=$BAIKAL_USER&witness[username]=1&data[displayname]=Test+User&witness[displayname]=1&data[email]=test@example.com&witness[email]=1&data[password]=$BAIKAL_PASS&witness[password]=1&data[passwordconfirm]=$BAIKAL_PASS&witness[passwordconfirm]=1" \
            "$BAIKAL_URL/admin/?/users/new/1/" 2>/dev/null || echo "000")

        if [ "$USER_RESPONSE" = "302" ] || [ "$USER_RESPONSE" = "200" ]; then
            log_info "Test user created (HTTP $USER_RESPONSE)"
        else
            log_warn "User creation returned HTTP $USER_RESPONSE (may already exist)"
        fi
    else
        log_warn "Could not get user creation page (not logged in?)"
    fi

    rm -f "$COOKIES"

    # Create calendar via MKCALENDAR
    log_info "Creating test calendar via MKCALENDAR..."
    CALENDAR_URL="$BAIKAL_URL/dav.php/calendars/$BAIKAL_USER/personal/"

    MKCALENDAR_RESPONSE=$(curl -s -o /dev/null -w "%{http_code}" \
        -u "$BAIKAL_USER:$BAIKAL_PASS" \
        -X MKCALENDAR \
        -H "Content-Type: application/xml; charset=utf-8" \
        --data '<?xml version="1.0" encoding="utf-8" ?>
<C:mkcalendar xmlns:D="DAV:" xmlns:C="urn:ietf:params:xml:ns:caldav">
  <D:set>
    <D:prop>
      <D:displayname>Personal</D:displayname>
      <C:supported-calendar-component-set>
        <C:comp name="VEVENT"/>
      </C:supported-calendar-component-set>
    </D:prop>
  </D:set>
</C:mkcalendar>' \
        "$CALENDAR_URL" 2>/dev/null || echo "000")

    if [ "$MKCALENDAR_RESPONSE" = "201" ]; then
        log_info "Test calendar created successfully"
    elif [ "$MKCALENDAR_RESPONSE" = "405" ] || [ "$MKCALENDAR_RESPONSE" = "409" ]; then
        log_info "Calendar already exists (HTTP $MKCALENDAR_RESPONSE)"
    else
        log_warn "MKCALENDAR returned HTTP $MKCALENDAR_RESPONSE"
    fi
else
    log_info "Skipping Docker setup, using existing Baikal at $BAIKAL_URL"
fi

# Verify CalDAV endpoint
log_info "Verifying CalDAV endpoint..."
HTTP_CODE=$(curl -s -o /dev/null -w "%{http_code}" \
    -u "$BAIKAL_USER:$BAIKAL_PASS" \
    "$BAIKAL_URL/dav.php/" 2>/dev/null || echo "000")

if [ "$HTTP_CODE" = "200" ] || [ "$HTTP_CODE" = "207" ]; then
    log_info "CalDAV endpoint accessible (HTTP $HTTP_CODE)"
else
    log_warn "CalDAV endpoint returned HTTP $HTTP_CODE (may still work)"
fi

# Run integration tests
log_info "Running integration tests..."
echo ""
echo "=============================================="
echo "  Baikal CalDAV Integration Tests"
echo "=============================================="
echo ""

export BAIKAL_URL
export BAIKAL_USER
export BAIKAL_PASS

# Run tests with Gradle (--rerun forces test execution even if cached)
./gradlew :icaldav-client:test \
    --tests "*BaikalIntegrationTest*" \
    --info \
    --rerun \
    2>&1 | tee baikal-test-output.log

TEST_EXIT_CODE=${PIPESTATUS[0]}

echo ""
echo "=============================================="

if [ $TEST_EXIT_CODE -eq 0 ]; then
    log_info "All integration tests passed!"
else
    log_error "Some integration tests failed (exit code: $TEST_EXIT_CODE)"
fi

# Print test report location
if [ -f "icaldav-client/build/reports/tests/test/index.html" ]; then
    log_info "Test report: icaldav-client/build/reports/tests/test/index.html"
fi

if [ "$KEEP_RUNNING" = true ]; then
    log_info "Baikal is still running at $BAIKAL_URL"
    log_info "Admin interface: $BAIKAL_URL/admin/"
    log_info "To stop it: docker-compose -f $COMPOSE_FILE down -v"
fi

exit $TEST_EXIT_CODE
