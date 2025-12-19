package gobridge

import (
	"bufio"
	"context"
	"fmt"
	"os"
	"strings"

	"github.com/threatexpert/gonc/v2/apps"
	"github.com/threatexpert/gonc/v2/misc"

	"github.com/xjasonlyu/tun2socks/v2/engine"

	_ "golang.org/x/mobile/bind"
	_ "golang.org/x/mobile/gl"
)

// Logger interface for Android to receive logs
type Logger interface {
	Log(message string)
}

var androidLogger Logger
var goncCancel context.CancelFunc

// SetLogger sets the logger for Android
func SetLogger(l Logger) {
	androidLogger = l
	// Redirect stdout/stderr to a custom writer that forwards to Android
	r, w, _ := os.Pipe()
	os.Stdout = w
	os.Stderr = w

	go func() {
		scanner := bufio.NewScanner(r)
		for scanner.Scan() {
			if androidLogger != nil {
				androidLogger.Log(scanner.Text())
			}
		}
	}()
}

// LogWriter implements io.Writer to forward logs to Android
type LogWriter struct{}

func (w *LogWriter) Write(p []byte) (n int, err error) {
	msg := string(p)
	if androidLogger != nil {
		androidLogger.Log(strings.TrimSpace(msg))
	}
	return len(p), nil
}

// StartGonc starts the gonc P2P connection.
// This function blocks, so it should be run in a goroutine on the Android side.
func StartGonc(args string) {
	defer func() {
		if r := recover(); r != nil {
			if androidLogger != nil {
				androidLogger.Log(fmt.Sprintf("PANIC in StartGonc: %v", r))
			}
		}
	}()

	// Create context for cancellation
	ctx, cancel := context.WithCancel(context.Background())
	goncCancel = cancel

	argSlice := strings.Split(args, " ")
	// Filter empty strings if any
	var cleanArgs []string
	// cleanArgs = append(cleanArgs, "gonc") // argv0 is handled by AppNetcatConfigByArgs? No, typically args[0] is progname.
	// Check apps.nc usage. Main calls it with os.Args[1:].
	// App_Netcat_main calls AppNetcatConfigByArgs("gonc", args).
	// usage: config, err := AppNetcatConfigByArgs("gonc", args)

	for _, arg := range argSlice {
		if strings.TrimSpace(arg) != "" {
			cleanArgs = append(cleanArgs, arg)
		}
	}

	console := &misc.ConsoleIO{}

	// Manually parse args and run to inject context
	config, err := apps.AppNetcatConfigByArgs("gonc", cleanArgs)
	if err != nil {
		if androidLogger != nil {
			androidLogger.Log(fmt.Sprintf("Error parsing gonc args: %v", err))
		}
		return
	}
	config.ConsoleMode = true
	config.GlobalCtx = ctx

	apps.App_Netcat_main_withconfig(console, config)
}

// StopGonc stops the gonc execution.
func StopGonc() {
	if goncCancel != nil {
		goncCancel()
		// goncCancel = nil // Keep it until next Start?
		// Better to leave it, StartGonc overwrites it.
	}
}

// StartTun2Socks starts the tun2socks engine with the given file descriptor.
// fd: The file descriptor of the TUN interface (as an int).
// proxyUrl: The SOCKS5 proxy URL (e.g., "socks5://127.0.0.1:1080").
// deviceName: The name of the device (not strictly needed for fd:// but good for logging).
// mtu: The MTU of the interface.
func StartTun2Socks(fd int, proxyUrl string, deviceName string, mtu int, logLevel string) {
	defer func() {
		if r := recover(); r != nil {
			if androidLogger != nil {
				androidLogger.Log(fmt.Sprintf("PANIC in StartTun2Socks: %v", r))
			}
		}
	}()
	key := &engine.Key{
		Device:   fmt.Sprintf("fd://%d", fd),
		Proxy:    proxyUrl,
		LogLevel: logLevel,
		MTU:      mtu,
	}

	engine.Insert(key)
	engine.Start()
}

// StopTun2Socks stops the tun2socks engine.
func StopTun2Socks() {
	defer func() {
		if r := recover(); r != nil {
			if androidLogger != nil {
				androidLogger.Log(fmt.Sprintf("PANIC in StopTun2Socks: %v", r))
			}
		}
	}()
	engine.Stop()
}
