#!/bin/bash
#
# Run CalDAV integration tests against Google Calendar.
#
# Requires OAuth2 credentials (see setup instructions below).
#
# Usage:
#   ./run-google-tests.sh           # Run all Google Calendar tests
#   ./run-google-tests.sh --setup   # Show setup instructions
#
# Environment variables:
#   GOOGLE_REFRESH_TOKEN  - OAuth2 refresh token
#   GOOGLE_CLIENT_ID      - OAuth2 client ID
#   GOOGLE_CLIENT_SECRET  - OAuth2 client secret
#   GOOGLE_CALENDAR_ID    - Calendar ID (optional, default: primary)

set -e

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$SCRIPT_DIR"

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

show_setup() {
    cat << 'EOF'

========================================
GOOGLE CALENDAR CALDAV SETUP GUIDE
========================================

Google Calendar CalDAV requires OAuth2 authentication.
Follow these steps to obtain the required credentials:

1. CREATE OAUTH2 CREDENTIALS
   - Go to Google Cloud Console: https://console.cloud.google.com
   - Create a new project (or select existing)
   - Enable the Google Calendar API
   - Go to "APIs & Services" > "Credentials"
   - Create OAuth 2.0 Client ID (Desktop app)
   - Note the Client ID and Client Secret

2. GET REFRESH TOKEN
   Option A: OAuth Playground (easiest)
   - Go to: https://developers.google.com/oauthplayground/
   - Click gear icon, check "Use your own OAuth credentials"
   - Enter your Client ID and Secret
   - In Step 1, select "Google Calendar API v3" and authorize
   - In Step 2, click "Exchange authorization code for tokens"
   - Copy the Refresh token

   Option B: Manual flow
   - Use your OAuth credentials to complete the authorization flow
   - Exchange the authorization code for tokens
   - Save the refresh_token (it doesn't expire)

3. SET ENVIRONMENT VARIABLES
   export GOOGLE_REFRESH_TOKEN="your-refresh-token"
   export GOOGLE_CLIENT_ID="your-client-id.apps.googleusercontent.com"
   export GOOGLE_CLIENT_SECRET="your-client-secret"

   # Optional: specific calendar (default is 'primary')
   export GOOGLE_CALENDAR_ID="your-calendar-id@group.calendar.google.com"

4. RUN TESTS
   ./run-google-tests.sh

========================================
NOTES
========================================

- Refresh tokens don't expire unless revoked
- Tests will create events in your calendar (cleaned up automatically)
- Use a test calendar (not your main calendar) for safety
- Google CalDAV limitations:
  * No MKCALENDAR (can't create calendars via CalDAV)
  * No VTODO/VJOURNAL support
  * OAuth2 only (no basic auth)

EOF
}

# Parse arguments
for arg in "$@"; do
    case $arg in
        --setup|--help)
            show_setup
            exit 0
            ;;
    esac
done

# Check for required environment variables
MISSING_VARS=""
if [ -z "$GOOGLE_REFRESH_TOKEN" ]; then
    MISSING_VARS="$MISSING_VARS GOOGLE_REFRESH_TOKEN"
fi
if [ -z "$GOOGLE_CLIENT_ID" ]; then
    MISSING_VARS="$MISSING_VARS GOOGLE_CLIENT_ID"
fi
if [ -z "$GOOGLE_CLIENT_SECRET" ]; then
    MISSING_VARS="$MISSING_VARS GOOGLE_CLIENT_SECRET"
fi

if [ -n "$MISSING_VARS" ]; then
    log_error "Missing required environment variables:$MISSING_VARS"
    echo ""
    log_info "Run './run-google-tests.sh --setup' for setup instructions"
    exit 1
fi

log_info "Google Calendar credentials configured"
log_info "Calendar ID: ${GOOGLE_CALENDAR_ID:-primary}"

# Run integration tests
echo ""
echo "=============================================="
echo "  Google Calendar CalDAV Integration Tests"
echo "=============================================="
echo ""

# Export variables for tests
export GOOGLE_REFRESH_TOKEN
export GOOGLE_CLIENT_ID
export GOOGLE_CLIENT_SECRET
export GOOGLE_CALENDAR_ID

# Run tests with Gradle (--rerun forces test execution even if cached)
./gradlew :icaldav-client:test \
    --tests "*GoogleCalendarIntegrationTest*" \
    --info \
    --rerun \
    2>&1 | tee google-test-output.log

TEST_EXIT_CODE=${PIPESTATUS[0]}

echo ""
echo "=============================================="

if [ $TEST_EXIT_CODE -eq 0 ]; then
    log_info "All Google Calendar tests passed!"
else
    log_error "Some tests failed (exit code: $TEST_EXIT_CODE)"
fi

# Print test report location
if [ -f "icaldav-client/build/reports/tests/test/index.html" ]; then
    log_info "Test report: icaldav-client/build/reports/tests/test/index.html"
fi

exit $TEST_EXIT_CODE
