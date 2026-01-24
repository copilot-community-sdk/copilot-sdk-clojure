#!/bin/bash
# Deploy copilot-sdk to Clojars and Maven Central

set -e

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

check_env_var() {
    local var_name=$1
    if [[ -z "${!var_name}" ]]; then
        log_error "Environment variable $var_name is not set"
        return 1
    fi
    log_info "$var_name is set"
    return 0
}

check_settings_xml() {
    local settings_file="$HOME/.m2/settings.xml"

    if [[ ! -f "$settings_file" ]]; then
        log_error "Maven settings file not found: $settings_file"
        log_error "Create it with your Clojars and Maven Central credentials"
        return 1
    fi

    log_info "Found Maven settings: $settings_file"

    # Check for central/ossrh server config
    if ! grep -q '<id>central</id>' "$settings_file" && ! grep -q '<id>ossrh</id>' "$settings_file"; then
        log_warn "No 'central' or 'ossrh' server found in settings.xml - Maven Central deploy may fail"
    else
        log_info "Found Maven Central server configuration"
    fi

    return 0
}

deploy_clojars() {
    log_info "Deploying to Clojars..."
    if ! clj -T:build deploy; then
        log_error "Clojars deployment failed"
        return 1
    fi
    log_info "Successfully deployed to Clojars"
    return 0
}

deploy_central() {
    log_info "Deploying to Maven Central..."
    if ! clj -T:build deploy-central; then
        log_error "Maven Central deployment failed"
        return 1
    fi
    log_info "Successfully deployed to Maven Central"
    return 0
}

main() {
    echo "=============================================="
    echo "  Copilot SDK Deployment Script"
    echo "=============================================="
    echo ""

    # Check prerequisites
    log_info "Checking prerequisites..."

    local checks_passed=true

    if ! check_env_var "CLOJARS_USERNAME"; then
        checks_passed=false
    fi

    if ! check_env_var "CLOJARS_PASSWORD"; then
        checks_passed=false
    fi

    if ! check_settings_xml; then
        checks_passed=false
    fi

    if [[ "$checks_passed" == "false" ]]; then
        echo ""
        log_error "Prerequisites check failed. Please fix the issues above."
        echo ""
        echo "Required setup:"
        echo "  1. Set CLOJARS_USERNAME and CLOJARS_PASSWORD environment variables"
        echo "  2. Configure ~/.m2/settings.xml with server credentials:"
        echo ""
        echo "     <settings>"
        echo "       <servers>"
        echo "         <server>"
        echo "           <id>central</id>"
        echo "           <username>your-central-username</username>"
        echo "           <password>your-central-token</password>"
        echo "         </server>"
        echo "       </servers>"
        echo "     </settings>"
        echo ""
        exit 1
    fi

    echo ""
    log_info "All prerequisites met"
    echo ""

    # Deploy to Clojars
    if ! deploy_clojars; then
        exit 1
    fi

    echo ""

    # Deploy to Maven Central
    if ! deploy_central; then
        log_warn "Maven Central deployment failed, but Clojars deployment succeeded"
        exit 1
    fi

    echo ""
    echo "=============================================="
    log_info "All deployments completed successfully!"
    echo "=============================================="
}

main "$@"
