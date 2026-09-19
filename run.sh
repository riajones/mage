#!/usr/bin/env bash
#
# run.sh - Start both XMage Server and Client concurrently
#

# ANSI colors for pretty terminal output
BOLD='\033[1m'
GREEN='\033[0;32m'
YELLOW='\033[0;33m'
RED='\033[0;31m'
BLUE='\033[0;34m'
CYAN='\033[0;36m'
NC='\033[0m' # No Color

# Determine script and project directory
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
if [ -f "$SCRIPT_DIR/pom.xml" ]; then
    PROJECT_ROOT="$SCRIPT_DIR"
elif [ -f "$SCRIPT_DIR/../pom.xml" ]; then
    PROJECT_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
else
    PROJECT_ROOT="$SCRIPT_DIR"
fi

# Ensure JAVA_HOME points to Java 8 if unset (required for XMage on macOS / systems with multiple JDKs)
if [ -z "${JAVA_HOME:-}" ] && command -v /usr/libexec/java_home >/dev/null 2>&1; then
    JAVA_8_HOME=$(/usr/libexec/java_home -v 1.8 2>/dev/null || /usr/libexec/java_home 2>/dev/null)
    if [ -n "$JAVA_8_HOME" ]; then
        export JAVA_HOME="$JAVA_8_HOME"
    fi
fi

# Resolve deploy directories
if [ -d "$PROJECT_ROOT/deploy/server" ] && [ -d "$PROJECT_ROOT/deploy/client" ]; then
    DEPLOY_DIR="$PROJECT_ROOT/deploy"
    SERVER_DIR="$DEPLOY_DIR/server"
    CLIENT_DIR="$DEPLOY_DIR/client"
elif [ -d "$SCRIPT_DIR/server" ] && [ -d "$SCRIPT_DIR/client" ]; then
    DEPLOY_DIR="$SCRIPT_DIR"
    SERVER_DIR="$DEPLOY_DIR/server"
    CLIENT_DIR="$DEPLOY_DIR/client"
else
    DEPLOY_DIR="$PROJECT_ROOT/deploy"
    SERVER_DIR="$DEPLOY_DIR/server"
    CLIENT_DIR="$DEPLOY_DIR/client"
fi

# Helper function to check if a port is open
check_port() {
    local port="$1"
    if command -v nc >/dev/null 2>&1; then
        nc -z 127.0.0.1 "$port" 2>/dev/null
    elif command -v lsof >/dev/null 2>&1; then
        lsof -iTCP:"$port" -sTCP:LISTEN >/dev/null 2>&1
    else
        (exec 3<>/dev/tcp/127.0.0.1/"$port") 2>/dev/null
    fi
}

# Display usage instructions
show_help() {
    cat << EOF
Usage: $(basename "$0") [options] [-- [client-options]]

Starts the XMage server and client at the same time.
When you exit the XMage client or press Ctrl+C, the server will also automatically stop.

Build Options:
  -b, --build            Build and package the project before running
      --clean            Clean before building (use with -b or --build-only)
      --build-only       Build and package without running

Run Options:
  -k, --keep-server      Keep the server running in background after client closes
  -s, --server-only      Start only the XMage server
  -c, --client-only      Start only the XMage client
  --server-mem <mem>     Memory limit for server JVM (default: 1024m)
  --client-mem <mem>     Memory limit for client JVM (default: 2000m)
  -h, --help             Show this help message

Any additional arguments (e.g. -debug, -lite) are passed directly to the XMage client.

Examples:
  ./run.sh                  # Start server and client together
  ./run.sh -b               # Build project first, then start both
  ./run.sh -b --clean       # Clean build first, then start both
  ./run.sh --build-only     # Build and package without starting
  ./run.sh --keep-server    # Start both, leave server running when client closes
  ./run.sh -debug           # Start both with client debug menus enabled
EOF
}

# Defaults
BUILD=0
BUILD_ONLY=0
CLEAN=0
KEEP_SERVER=0
SERVER_ONLY=0
CLIENT_ONLY=0
SERVER_MEM="${SERVER_MEM:-1024m}"
CLIENT_MEM="${CLIENT_MEM:-2000m}"
SERVER_PORT=17171
CLIENT_ARGS=()

# Parse arguments
while [[ $# -gt 0 ]]; do
    case "$1" in
        -h|--help)
            show_help
            exit 0
            ;;
        -b|--build)
            BUILD=1
            shift
            ;;
        --clean)
            CLEAN=1
            shift
            ;;
        --build-only)
            BUILD=1
            BUILD_ONLY=1
            shift
            ;;
        -k|--keep-server)
            KEEP_SERVER=1
            shift
            ;;
        -s|--server-only)
            SERVER_ONLY=1
            shift
            ;;
        -c|--client-only)
            CLIENT_ONLY=1
            shift
            ;;
        --server-mem)
            SERVER_MEM="$2"
            shift 2
            ;;
        --client-mem)
            CLIENT_MEM="$2"
            shift 2
            ;;
        --)
            shift
            while [[ $# -gt 0 ]]; do
                CLIENT_ARGS+=("$1")
                shift
            done
            break
            ;;
        *)
            CLIENT_ARGS+=("$1")
            shift
            ;;
    esac
done

# Verify Java is available
if ! command -v java >/dev/null 2>&1; then
    echo -e "${RED}Error: 'java' is not installed or not found in PATH.${NC}"
    echo "Please install Java (version 8 or later is recommended for XMage)."
    exit 1
fi

# Build project if requested
if [ "$BUILD" -eq 1 ]; then
    if ! command -v mvn >/dev/null 2>&1; then
        echo -e "${RED}Error: 'mvn' (Maven) is not installed or not found in PATH.${NC}"
        exit 1
    fi

    echo -e "${BOLD}${CYAN}=== Building XMage ===${NC}"
    BUILD_CMD=("mvn")
    if [ "$CLEAN" -eq 1 ]; then
        BUILD_CMD+=("clean")
    fi
    BUILD_CMD+=("install" "package" "-DskipTests")

    echo -e "${BLUE}Running: ${BUILD_CMD[*]}${NC}"
    (cd "$PROJECT_ROOT" && "${BUILD_CMD[@]}") || {
        echo -e "${RED}Error: Maven build failed.${NC}"
        exit 1
    }

    echo -e "${BLUE}Packaging assemblies (Mage.Client & Mage.Server)...${NC}"
    (cd "$PROJECT_ROOT/Mage.Client" && mvn package assembly:single -DskipTests) || {
        echo -e "${RED}Error: Mage.Client assembly packaging failed.${NC}"
        exit 1
    }
    (cd "$PROJECT_ROOT/Mage.Server" && mvn package assembly:single -DskipTests) || {
        echo -e "${RED}Error: Mage.Server assembly packaging failed.${NC}"
        exit 1
    }

    echo -e "${BLUE}Deploying assemblies to $DEPLOY_DIR...${NC}"
    mkdir -p "$DEPLOY_DIR"
    cp "$PROJECT_ROOT/Mage.Server/target/mage-server.zip" "$DEPLOY_DIR/"
    cp "$PROJECT_ROOT/Mage.Client/target/mage-client.zip" "$DEPLOY_DIR/"

    mkdir -p "$SERVER_DIR" "$CLIENT_DIR"
    unzip -q -o "$DEPLOY_DIR/mage-server.zip" -d "$SERVER_DIR"
    unzip -q -o "$DEPLOY_DIR/mage-client.zip" -d "$CLIENT_DIR"

    echo -e "${GREEN}Build and packaging completed successfully!${NC}"

    if [ "$BUILD_ONLY" -eq 1 ]; then
        echo -e "${GREEN}Build complete. Exiting (--build-only specified).${NC}"
        exit 0
    fi
fi

# Auto-unpack if zip files exist in deploy/ but folders do not
if [ ! -d "$SERVER_DIR" ] && [ -f "$DEPLOY_DIR/mage-server.zip" ]; then
    echo -e "${YELLOW}Extracting mage-server.zip into $SERVER_DIR...${NC}"
    mkdir -p "$SERVER_DIR"
    unzip -q -o "$DEPLOY_DIR/mage-server.zip" -d "$SERVER_DIR"
fi

if [ ! -d "$CLIENT_DIR" ] && [ -f "$DEPLOY_DIR/mage-client.zip" ]; then
    echo -e "${YELLOW}Extracting mage-client.zip into $CLIENT_DIR...${NC}"
    mkdir -p "$CLIENT_DIR"
    unzip -q -o "$DEPLOY_DIR/mage-client.zip" -d "$CLIENT_DIR"
fi

# Also check root target directories if deploy didn't have them
if [ ! -d "$SERVER_DIR" ] && [ -f "$PROJECT_ROOT/Mage.Server/target/mage-server.zip" ]; then
    echo -e "${YELLOW}Extracting Mage.Server/target/mage-server.zip into $SERVER_DIR...${NC}"
    mkdir -p "$SERVER_DIR"
    unzip -q -o "$PROJECT_ROOT/Mage.Server/target/mage-server.zip" -d "$SERVER_DIR"
fi

if [ ! -d "$CLIENT_DIR" ] && [ -f "$PROJECT_ROOT/Mage.Client/target/mage-client.zip" ]; then
    echo -e "${YELLOW}Extracting Mage.Client/target/mage-client.zip into $CLIENT_DIR...${NC}"
    mkdir -p "$CLIENT_DIR"
    unzip -q -o "$PROJECT_ROOT/Mage.Client/target/mage-client.zip" -d "$CLIENT_DIR"
fi

# Find server and client jars dynamically
SERVER_JAR=""
CLIENT_JAR=""
if [ -d "$SERVER_DIR/lib" ]; then
    SERVER_JAR=$(find "$SERVER_DIR/lib" -maxdepth 1 -name "mage-server-*.jar" 2>/dev/null | head -n 1)
fi
if [ -d "$CLIENT_DIR/lib" ]; then
    CLIENT_JAR=$(find "$CLIENT_DIR/lib" -maxdepth 1 -name "mage-client-*.jar" 2>/dev/null | head -n 1)
fi

# Verify required files exist
if [ "$CLIENT_ONLY" -eq 0 ] && [ -z "$SERVER_JAR" ]; then
    echo -e "${RED}Error: XMage server jar not found in '$SERVER_DIR/lib'.${NC}"
    echo "You can build and run in one command with:"
    echo "  ./run.sh -b"
    exit 1
fi

if [ "$SERVER_ONLY" -eq 0 ] && [ -z "$CLIENT_JAR" ]; then
    echo -e "${RED}Error: XMage client jar not found in '$CLIENT_DIR/lib'.${NC}"
    echo "You can build and run in one command with:"
    echo "  ./run.sh -b"
    exit 1
fi

# Process tracking variables
SERVER_PID=""
CLIENT_PID=""
STARTED_SERVER=0
CLEANED_UP=0

cleanup() {
    if [ "$CLEANED_UP" -eq 1 ]; then
        return
    fi
    CLEANED_UP=1

    echo ""
    # Stop client if it was started and is still running
    if [ -n "$CLIENT_PID" ] && kill -0 "$CLIENT_PID" 2>/dev/null; then
        echo -e "${YELLOW}Stopping XMage Client (PID: $CLIENT_PID)...${NC}"
        kill "$CLIENT_PID" 2>/dev/null || true
    fi

    # Stop server if WE started it
    if [ "$STARTED_SERVER" -eq 1 ] && [ -n "$SERVER_PID" ] && kill -0 "$SERVER_PID" 2>/dev/null; then
        if [ "$KEEP_SERVER" -eq 1 ]; then
            echo -e "${BLUE}XMage Server kept running (PID: $SERVER_PID on port $SERVER_PORT).${NC}"
        else
            echo -e "${YELLOW}Stopping XMage Server (PID: $SERVER_PID)...${NC}"
            kill "$SERVER_PID" 2>/dev/null || true
            for _ in {1..5}; do
                if ! kill -0 "$SERVER_PID" 2>/dev/null; then
                    break
                fi
                sleep 1
            done
            if kill -0 "$SERVER_PID" 2>/dev/null; then
                kill -9 "$SERVER_PID" 2>/dev/null || true
            fi
            echo -e "${GREEN}XMage Server stopped.${NC}"
        fi
    fi
}

# Trap signals for graceful shutdown
trap cleanup INT TERM EXIT

echo -e "${BOLD}${CYAN}=== XMage Launcher ===${NC}"

# Start Server (if not client-only)
if [ "$CLIENT_ONLY" -eq 0 ]; then
    if check_port "$SERVER_PORT"; then
        echo -e "${YELLOW}Port $SERVER_PORT is already in use.${NC}"
        echo -e "${BLUE}Assuming an XMage server is already running. Skipping server launch.${NC}"
        STARTED_SERVER=0
    else
        echo -e "${BLUE}Starting XMage Server (-Xmx$SERVER_MEM)...${NC}"
        (
            cd "$SERVER_DIR" || exit 1
            exec java -Xmx"$SERVER_MEM" -Dfile.encoding=UTF-8 -jar "$SERVER_JAR"
        ) > "$SERVER_DIR/mageserver-console.log" 2>&1 &
        SERVER_PID=$!
        STARTED_SERVER=1
        echo -e "Server process launched (PID: ${BOLD}$SERVER_PID${NC}). Waiting for port $SERVER_PORT..."

        # Wait up to 30 seconds for server port to open
        MAX_WAIT=30
        WAITED=0
        SERVER_READY=0
        while [ "$WAITED" -lt "$MAX_WAIT" ]; do
            if ! kill -0 "$SERVER_PID" 2>/dev/null; then
                echo -e "${RED}Error: Server process exited unexpectedly!${NC}"
                echo -e "${YELLOW}Check server logs at: $SERVER_DIR/mageserver.log or $SERVER_DIR/mageserver-console.log${NC}"
                tail -n 20 "$SERVER_DIR/mageserver-console.log" 2>/dev/null
                exit 1
            fi
            if check_port "$SERVER_PORT"; then
                SERVER_READY=1
                break
            fi
            sleep 1
            WAITED=$((WAITED + 1))
            printf "."
        done
        printf "\n"

        if [ "$SERVER_READY" -eq 1 ]; then
            echo -e "${GREEN}XMage Server is up and listening on port $SERVER_PORT!${NC}"
        else
            echo -e "${YELLOW}Warning: Server port $SERVER_PORT did not respond within $MAX_WAIT seconds.${NC}"
            echo -e "${YELLOW}Proceeding to launch client anyway...${NC}"
        fi
    fi
fi

# If server-only mode, wait for server to terminate
if [ "$SERVER_ONLY" -eq 1 ]; then
    if [ "$STARTED_SERVER" -eq 1 ]; then
        echo -e "${GREEN}Server running. Press Ctrl+C to stop.${NC}"
        wait "$SERVER_PID"
    else
        echo -e "${BLUE}Server was already running. Nothing to do.${NC}"
    fi
    exit 0
fi

# Start Client
echo -e "${BLUE}Starting XMage Client (-Xmx$CLIENT_MEM)...${NC}"
(
    cd "$CLIENT_DIR" || exit 1
    if [ ${#CLIENT_ARGS[@]} -gt 0 ]; then
        exec java -Xmx"$CLIENT_MEM" -Dfile.encoding=UTF-8 -jar "$CLIENT_JAR" "${CLIENT_ARGS[@]}"
    else
        exec java -Xmx"$CLIENT_MEM" -Dfile.encoding=UTF-8 -jar "$CLIENT_JAR"
    fi
) &
CLIENT_PID=$!
echo -e "${GREEN}XMage Client started (PID: ${BOLD}$CLIENT_PID${NC}).${NC}"
echo -e "${CYAN}Both client and server are running.${NC}"
echo -e "Press ${BOLD}Ctrl+C${NC} in this terminal or close the XMage client to stop both."

# Wait for client to exit
wait "$CLIENT_PID" 2>/dev/null || true
CLIENT_EXIT_CODE=$?

echo -e "\n${BLUE}XMage Client closed (exit code: $CLIENT_EXIT_CODE).${NC}"
exit "$CLIENT_EXIT_CODE"
