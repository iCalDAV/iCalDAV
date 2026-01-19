#!/bin/bash
#
# Run CalDAV integration tests against iCloud.
#
# Requires an Apple ID with app-specific password.
#
# Usage:
#   ./run-icloud-tests.sh           # Run all iCloud tests
#   ./run-icloud-tests.sh --setup   # Show setup instructions
#
# Environment variables:
#   ICLOUD_USERNAME      - Apple ID email
#   ICLOUD_APP_PASSWORD  - App-specific password

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
ICLOUD CALDAV SETUP GUIDE
========================================

iCloud CalDAV requires an app-specific password for third-party apps.
Follow these steps to obtain the required credentials:

1. ENABLE TWO-FACTOR AUTHENTICATION
   - Go to https://appleid.apple.com
   - Sign in with your Apple ID
   - Enable Two-Factor Authentication if not already enabled

2. GENERATE APP-SPECIFIC PASSWORD
   - Go to https://appleid.apple.com
   - Sign in and go to "Sign-In and Security"
   - Click "App-Specific Passwords"
   - Click "Generate an app-specific password"
   - Name it "iCalDAV Tests" or similar
   - Copy the generated password (format: xxxx-xxxx-xxxx-xxxx)

3. SET ENVIRONMENT VARIABLES

   Option A: Export in your shell:

     export ICLOUD_USERNAME="your_apple_id@icloud.com"
     export ICLOUD_APP_PASSWORD="xxxx-xxxx-xxxx-xxxx"

   Option B: Create a .env file in the project root:

     ICLOUD_USERNAME=your_apple_id@icloud.com
     ICLOUD_APP_PASSWORD=xxxx-xxxx-xxxx-xxxx

4. RUN THE TESTS

     ./run-icloud-tests.sh

NOTES:
- App-specific passwords do NOT expire
- You can revoke them at any time from appleid.apple.com
- iCloud CalDAV has eventual consistency - newly created events
  may not appear immediately in queries
- The CalDAV server URL is: https://caldav.icloud.com

EOF
}

# Handle --setup flag
if [[ "$1" == "--setup" ]]; then
    show_setup
    exit 0
fi

# Try to load from .env file if it exists
if [[ -f ".env" ]]; then
    log_info "Loading credentials from .env file"
    export $(grep -v '^#' .env | xargs)
fi

# Check for required credentials
if [[ -z "$ICLOUD_USERNAME" ]] || [[ -z "$ICLOUD_APP_PASSWORD" ]]; then
    log_error "iCloud credentials not configured!"
    echo ""
    echo "Required environment variables:"
    echo "  ICLOUD_USERNAME      - Your Apple ID email"
    echo "  ICLOUD_APP_PASSWORD  - App-specific password"
    echo ""
    echo "Run './run-icloud-tests.sh --setup' for setup instructions."
    exit 1
fi

log_info "Running iCloud CalDAV integration tests"
log_info "Username: ${ICLOUD_USERNAME:0:5}***"
log_info "Server: https://caldav.icloud.com"

echo ""
echo "=============================================="

# Run the tests
./gradlew :icaldav-client:test \
    --tests "org.onekash.icaldav.integration.ICloudIntegrationTest" \
    -Dorg.gradle.java.home="${JAVA_HOME:-}" \
    --info

TEST_EXIT_CODE=$?

echo "=============================================="

if [[ $TEST_EXIT_CODE -eq 0 ]]; then
    log_info "All iCloud integration tests passed!"
    log_info "Test report: icaldav-client/build/reports/tests/test/index.html"
else
    log_error "Some tests failed. Check the test report for details."
    log_info "Test report: icaldav-client/build/reports/tests/test/index.html"
fi

exit $TEST_EXIT_CODE
