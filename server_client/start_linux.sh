#!/usr/bin/env bash
# ============================================================
#  ALAKTRA MUSIC SERVER - 1-CLICK LAUNCHER FOR LINUX & PI
# ============================================================

set -e

# Change directory to the folder containing this script
cd "$(dirname "$0")"

# Colors for terminal output
CYAN='\033[0;36m'
GREEN='\033[0;32m'
RED='\033[0;31m'
YELLOW='\033[1;33m'
NC='\033[0m' # No Color

echo -e "${CYAN}============================================================${NC}"
echo -e "${CYAN}   ALAKTRA MUSIC SERVER - 1-CLICK LAUNCHER (LINUX & PI)    ${NC}"
echo -e "${CYAN}============================================================${NC}"
echo ""

# 1. Check for Python 3
if command -v python3 &>/dev/null; then
    PYTHON_CMD="python3"
elif command -v python &>/dev/null; then
    PYTHON_CMD="python"
else
    echo -e "${RED}[ERROR] Python 3 is not installed on this system!${NC}"
    echo "Install it via your package manager, for example:"
    echo "  sudo apt update && sudo apt install -y python3 python3-pip"
    exit 1
fi

echo -e "[1/3] Using: ${GREEN}$($PYTHON_CMD --version)${NC}"

# 2. Check and install dependencies
echo -e "[2/3] Checking dependencies..."
if [ -f "requirements.txt" ]; then
    $PYTHON_CMD -m pip install -r requirements.txt --quiet || {
        echo -e "${YELLOW}[WARNING] Could not install some packages via pip.${NC}"
        echo -e "${YELLOW}If you encounter missing modules, run: pip install -r requirements.txt${NC}"
    }
fi

# 3. Launch launcher.py
echo -e "[3/3] ${GREEN}Launching Alaktra Server Launcher with Tailscale detection...${NC}"
echo ""

chmod +x launcher.py 2>/dev/null || true
$PYTHON_CMD launcher.py "$@"
