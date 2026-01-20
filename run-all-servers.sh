#!/bin/bash
#
# Run CalDAV integration tests against multiple servers.
#
# Usage:
#   ./run-all-servers.sh                     # Run all servers sequentially
#   ./run-all-servers.sh --parallel          # Run all servers in parallel
#   ./run-all-servers.sh --server=nextcloud  # Run specific server only
#   ./run-all-servers.sh --server=baikal
#   ./run-all-servers.sh --server=radicale
#   ./run-all-servers.sh --keep              # Keep servers running after tests
#   ./run-all-servers.sh --skip-setup        # Skip Docker setup
#

set -e

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$SCRIPT_DIR"

# Server configuration
SERVERS=("nextcloud" "baikal" "radicale")
declare -A SERVER_SCRIPTS=(
    ["nextcloud"]="./run-integration-tests.sh"
    ["baikal"]="./run-baikal-tests.sh"
    ["radicale"]="./run-radicale-tests.sh"
)
declare -A SERVER_COMPOSE=(
    ["nextcloud"]="docker-compose.test.yml"
    ["baikal"]="docker-compose.baikal.yml"
    ["radicale"]="docker-compose.radicale.yml"
)
declare -A RESULTS

# Parse arguments
KEEP_RUNNING=false
SKIP_SETUP=false
PARALLEL=false
SELECTED_SERVER=""

for arg in "$@"; do
    case $arg in
        --keep)
            KEEP_RUNNING=true
            ;;
        --skip-setup)
            SKIP_SETUP=true
            ;;
        --parallel)
            PARALLEL=true
            ;;
        --server=*)
            SELECTED_SERVER="${arg#*=}"
            ;;
        --help|-h)
            echo "Usage: $0 [OPTIONS]"
            echo ""
            echo "Run CalDAV integration tests against multiple servers."
            echo ""
            echo "Options:"
            echo "  --server=NAME    Run specific server only (nextcloud, baikal, radicale)"
            echo "  --parallel       Run all servers in parallel (ports don't conflict)"
            echo "  --keep           Keep servers running after tests"
            echo "  --skip-setup     Skip Docker setup (use existing servers)"
            echo "  --help, -h       Show this help message"
            echo ""
            echo "Examples:"
            echo "  $0                          # Run all servers sequentially"
            echo "  $0 --parallel               # Run all servers in parallel"
            echo "  $0 --server=radicale        # Run Radicale only (fastest)"
            echo "  $0 --server=nextcloud --keep  # Run Nextcloud, keep running"
            exit 0
            ;;
        *)
            echo "Unknown argument: $arg"
            echo "Use --help for usage information."
            exit 1
            ;;
    esac
done

# Colors for output
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
BOLD='\033[1m'
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

log_header() {
    echo -e "${BLUE}${BOLD}$1${NC}"
}

# Detect docker-compose command
detect_docker_compose() {
    if command -v docker-compose &> /dev/null; then
        DOCKER_COMPOSE="docker-compose"
    elif docker compose version &> /dev/null; then
        DOCKER_COMPOSE="docker compose"
    else
        log_error "docker-compose is not installed."
        exit 1
    fi
}

# Cleanup function for trap
cleanup_all() {
    if [ "$KEEP_RUNNING" = false ] && [ "$SKIP_SETUP" = false ]; then
        log_info "Cleaning up all Docker containers..."
        for server in "${SERVERS[@]}"; do
            local compose_file="${SERVER_COMPOSE[$server]}"
            if [ -f "$compose_file" ]; then
                $DOCKER_COMPOSE -f "$compose_file" down -v 2>/dev/null || true
            fi
        done
    fi
}

# Validate selected server
validate_server() {
    local server=$1
    for valid in "${SERVERS[@]}"; do
        if [ "$valid" = "$server" ]; then
            return 0
        fi
    done
    return 1
}

# Run tests for a single server
run_server_tests() {
    local server=$1
    local script=${SERVER_SCRIPTS[$server]}
    local args=""

    [[ "$KEEP_RUNNING" = true ]] && args="$args --keep"
    [[ "$SKIP_SETUP" = true ]] && args="$args --skip-setup"

    log_header "=============================================="
    log_header "  Starting tests for: $server"
    log_header "=============================================="
    echo ""

    # Run the server-specific script
    set +e
    $script $args
    local exit_code=$?
    set -e

    return $exit_code
}

# Sequential execution
run_sequential() {
    local servers_to_run=("$@")

    for server in "${servers_to_run[@]}"; do
        run_server_tests "$server"
        RESULTS[$server]=$?
        echo ""
    done
}

# Parallel execution
run_parallel() {
    local servers_to_run=("$@")
    local pids=()
    local log_files=()

    log_info "Starting all servers in parallel..."
    echo ""

    # Start all servers in background
    for server in "${servers_to_run[@]}"; do
        local script=${SERVER_SCRIPTS[$server]}
        local args=""
        local log_file="${server}-parallel-output.log"

        [[ "$KEEP_RUNNING" = true ]] && args="$args --keep"
        [[ "$SKIP_SETUP" = true ]] && args="$args --skip-setup"

        log_info "Starting $server tests (output: $log_file)..."
        (
            $script $args > "$log_file" 2>&1
        ) &
        pids+=($!)
        log_files+=("$log_file")
    done

    echo ""
    log_info "Waiting for all servers to complete..."
    log_info "PIDs: ${pids[*]}"
    echo ""

    # Wait for each process and capture exit codes
    for i in "${!servers_to_run[@]}"; do
        local server="${servers_to_run[$i]}"
        local pid="${pids[$i]}"
        local log_file="${log_files[$i]}"

        set +e
        wait "$pid"
        RESULTS[$server]=$?
        set -e

        if [ "${RESULTS[$server]}" -eq 0 ]; then
            log_info "$server completed successfully"
        else
            log_error "$server failed (exit code: ${RESULTS[$server]})"
        fi
    done

    echo ""
    log_info "All parallel executions complete. Log files:"
    for log_file in "${log_files[@]}"; do
        echo "  - $log_file"
    done
}

# Print summary table
print_summary() {
    local servers_to_show=("$@")
    local all_passed=true

    echo ""
    echo "=============================================="
    echo "  Multi-Server Test Results"
    echo "=============================================="
    echo ""

    for server in "${servers_to_show[@]}"; do
        local result="${RESULTS[$server]}"
        if [ "$result" -eq 0 ]; then
            echo -e "  ${GREEN}[PASS]${NC} $server"
        else
            echo -e "  ${RED}[FAIL]${NC} $server (exit code: $result)"
            all_passed=false
        fi
    done

    echo ""
    echo "=============================================="

    if [ "$all_passed" = true ]; then
        echo -e "  ${GREEN}${BOLD}All servers passed!${NC}"
    else
        echo -e "  ${RED}${BOLD}Some servers failed.${NC}"
    fi

    echo "=============================================="
    echo ""
}

# Calculate overall exit code
get_overall_exit_code() {
    local servers_to_check=("$@")

    for server in "${servers_to_check[@]}"; do
        if [ "${RESULTS[$server]}" -ne 0 ]; then
            return 1
        fi
    done
    return 0
}

# Main execution
main() {
    # Check Docker
    if ! command -v docker &> /dev/null; then
        log_error "Docker is not installed. Please install Docker first."
        exit 1
    fi

    detect_docker_compose

    # Determine which servers to run
    local servers_to_run=()

    if [ -n "$SELECTED_SERVER" ]; then
        if ! validate_server "$SELECTED_SERVER"; then
            log_error "Unknown server: $SELECTED_SERVER"
            log_error "Valid servers: ${SERVERS[*]}"
            exit 1
        fi
        servers_to_run=("$SELECTED_SERVER")
    else
        servers_to_run=("${SERVERS[@]}")
    fi

    # Setup cleanup trap
    trap cleanup_all EXIT

    # Print header
    echo ""
    log_header "=============================================="
    log_header "  Multi-Server CalDAV Integration Tests"
    log_header "=============================================="
    echo ""
    log_info "Servers to test: ${servers_to_run[*]}"
    log_info "Parallel mode: $PARALLEL"
    log_info "Keep running: $KEEP_RUNNING"
    log_info "Skip setup: $SKIP_SETUP"
    echo ""

    # Run tests
    if [ "$PARALLEL" = true ] && [ ${#servers_to_run[@]} -gt 1 ]; then
        run_parallel "${servers_to_run[@]}"
    else
        run_sequential "${servers_to_run[@]}"
    fi

    # Print summary
    print_summary "${servers_to_run[@]}"

    # Return appropriate exit code
    if get_overall_exit_code "${servers_to_run[@]}"; then
        exit 0
    else
        exit 1
    fi
}

# Run main function
main
