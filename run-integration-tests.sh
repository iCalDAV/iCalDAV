#!/bin/bash
#
# Run CalDAV integration tests against a real Nextcloud instance.
#
# Usage:
#   ./run-integration-tests.sh           # Run all integration tests
#   ./run-integration-tests.sh --keep    # Keep Nextcloud running after tests
#   ./run-integration-tests.sh --skip-setup  # Skip Docker setup (use existing)
#

set -e

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$SCRIPT_DIR"

# Configuration
NEXTCLOUD_URL="http://localhost:8080"
NEXTCLOUD_USER="testuser"
NEXTCLOUD_PASS="testpass123"
COMPOSE_FILE="docker-compose.test.yml"
MAX_WAIT_SECONDS=120

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
            echo "  --keep        Keep Nextcloud running after tests"
            echo "  --skip-setup  Skip Docker setup (use existing Nextcloud)"
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
        log_info "Stopping Nextcloud..."
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

# Start Nextcloud
if [ "$SKIP_SETUP" = false ]; then
    log_info "Starting Nextcloud..."
    $DOCKER_COMPOSE -f "$COMPOSE_FILE" down -v 2>/dev/null || true
    $DOCKER_COMPOSE -f "$COMPOSE_FILE" up -d

    # Wait for Nextcloud to be ready
    log_info "Waiting for Nextcloud to be ready (max ${MAX_WAIT_SECONDS}s)..."

    elapsed=0
    while [ $elapsed -lt $MAX_WAIT_SECONDS ]; do
        if curl -s -o /dev/null -w "%{http_code}" "$NEXTCLOUD_URL/status.php" 2>/dev/null | grep -q "200"; then
            log_info "Nextcloud is ready!"
            break
        fi

        echo -n "."
        sleep 2
        elapsed=$((elapsed + 2))
    done
    echo ""

    if [ $elapsed -ge $MAX_WAIT_SECONDS ]; then
        log_error "Nextcloud did not become ready within ${MAX_WAIT_SECONDS} seconds"
        $DOCKER_COMPOSE -f "$COMPOSE_FILE" logs nextcloud
        exit 1
    fi

    # Additional wait for CalDAV to be fully initialized
    log_info "Waiting for CalDAV endpoint to be ready..."
    sleep 5
else
    log_info "Skipping Docker setup, using existing Nextcloud at $NEXTCLOUD_URL"
fi

# Verify CalDAV endpoint
log_info "Verifying CalDAV endpoint..."
HTTP_CODE=$(curl -s -o /dev/null -w "%{http_code}" \
    -u "$NEXTCLOUD_USER:$NEXTCLOUD_PASS" \
    "$NEXTCLOUD_URL/remote.php/dav" 2>/dev/null || echo "000")

if [ "$HTTP_CODE" != "200" ] && [ "$HTTP_CODE" != "207" ] && [ "$HTTP_CODE" != "401" ]; then
    log_warn "CalDAV endpoint returned HTTP $HTTP_CODE (may still work)"
fi

# Create test calendar via MKCALENDAR (fresh Nextcloud doesn't have calendars)
log_info "Creating test calendar via MKCALENDAR..."
CALENDAR_URL="$NEXTCLOUD_URL/remote.php/dav/calendars/$NEXTCLOUD_USER/personal/"

MKCALENDAR_RESPONSE=$(curl -s -o /dev/null -w "%{http_code}" \
    -u "$NEXTCLOUD_USER:$NEXTCLOUD_PASS" \
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
    log_warn "MKCALENDAR returned HTTP $MKCALENDAR_RESPONSE (may already exist)"
fi

# Run integration tests
log_info "Running integration tests..."
echo ""
echo "=============================================="
echo "  Nextcloud CalDAV Integration Tests"
echo "=============================================="
echo ""

export NEXTCLOUD_URL
export NEXTCLOUD_USER
export NEXTCLOUD_PASS

# Run tests with Gradle
./gradlew :caldav-core:test \
    --tests "*NextcloudIntegrationTest*" \
    --info \
    2>&1 | tee integration-test-output.log

TEST_EXIT_CODE=${PIPESTATUS[0]}

echo ""
echo "=============================================="

if [ $TEST_EXIT_CODE -eq 0 ]; then
    log_info "All integration tests passed!"
else
    log_error "Some integration tests failed (exit code: $TEST_EXIT_CODE)"
fi

# Print test report location
if [ -f "caldav-core/build/reports/tests/test/index.html" ]; then
    log_info "Test report: caldav-core/build/reports/tests/test/index.html"
fi

if [ "$KEEP_RUNNING" = true ]; then
    log_info "Nextcloud is still running at $NEXTCLOUD_URL"
    log_info "To stop it: docker-compose -f $COMPOSE_FILE down -v"
fi

exit $TEST_EXIT_CODE
