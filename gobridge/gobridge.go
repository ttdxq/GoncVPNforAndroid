package gobridge

import (
	"bufio"
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
	argSlice := strings.Split(args, " ")
	// Filter empty strings if any
	var cleanArgs []string
	cleanArgs = append(cleanArgs, "gonc") // argv0
	for _, arg := range argSlice {
		if strings.TrimSpace(arg) != "" {
			cleanArgs = append(cleanArgs, arg)
		}
	}

	console := &misc.ConsoleIO{}
	// We can't easily capture ConsoleIO output unless we modify gonc,
	// but we can capture stdout/stderr via SetLogger redirection above when initialized.

	// Create a config to redirect logs explicitly if possible, or just rely on os.Stdout/Stderr capture
	// apps.App_Netcat_main calls AppNetcatConfigByArgs which sets LogWriter to os.Stderr
	// So our pipe redirection in SetLogger should handle it.

	apps.App_Netcat_main(console, cleanArgs[1:])
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
