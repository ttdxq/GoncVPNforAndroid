---

# Android 内网访问 VPN（基于 gonc）的项目文档

## 1. 项目目标

本项目旨在开发一款 **Android 11+ VPN 应用**，用于在**无需公网 IP、无需传统 VPN 服务器**的情况下，让 Android 设备通过 **P2P 隧道安全访问内网资源**。

### 核心能力

* ✅ 仅接管 **指定内网网段（CIDR）**
* ✅ 支持 **TCP + UDP 数据转发**
* ✅ 基于 **gonc P2P**，无需公网服务器
* ✅ 路由器作为 **内网网关**
* ✅ Android 端 **VPN 级体验（VpnService）**
* ❌ 不接管系统 DNS（内网域名通过 hosts/IP 解决）

---

## 2. 总体架构

```
┌─────────────────────────┐
│       Android Apps      │
└───────────┬─────────────┘
            │
      (系统路由命中)
            │
┌───────────▼─────────────┐
│     Android VpnService  │
│  (仅接管内网 CIDR 路由)  │
└───────────┬─────────────┘
            │  TUN (IP 包)
┌───────────▼─────────────┐
│        tun2socks         │
│  TCP → SOCKS5 CONNECT    │
│  UDP → SOCKS5 ASSOCIATE  │
└───────────┬─────────────┘
            │  127.0.0.1:1080
┌───────────▼─────────────┐
│        gonc (客户端)     │
│  P2P 隧道 (-link 模式)   │
└───────────┬─────────────┘
            │
        TCP / UDP
            │
┌───────────▼─────────────┐
│   路由器 gonc 网关端     │
│     (-linkagent)        │
└───────────┬─────────────┘
            │
        内网访问
            │
┌───────────▼─────────────┐
│   内网任意 TCP/UDP 服务 │
└─────────────────────────┘
```

---

## 3. 路由器端部署规范（ARM 软路由）

### 角色定位

* 路由器作为 **内网 P2P 网关**
* 负责：

  * 接收 P2P 隧道
  * 转发客户端流量
  * 访问内网任意地址

### 启动命令（推荐）

```bash
gonc -p2p <SECRET_KEY> \
     -mqtt-wait \
     -linkagent \
     -keep-open \
     -tls \
     -psk <SECRET_KEY>
```

> 路由器端不需要 UI，本项目文档中暂不涉及 ACL/审计配置。

---

## 4. Android 端技术选型

| 模块     | 技术                      |
| ------ | ----------------------- |
| 语言     | Kotlin                  |
| 最低系统   | Android 11 (API 30)     |
| VPN 框架 | Android `VpnService`    |
| 转发引擎   | tun2socks（支持 UDP）       |
| P2P 通道 | gonc                    |
| 代理协议   | SOCKS5（含 UDP ASSOCIATE） |
| 配置存储   | DataStore               |
| 保活     | ForegroundService       |

---

## 5. Android 工程模块划分

```
app/
├── core/
│   ├── VpnControllerService.kt   # 前台服务 / 状态机
│   ├── VpnTunnelService.kt       # VpnService，建立 TUN
│   ├── RouteConfig.kt            # CIDR 路由模型
│   └── NetworkMonitor.kt         # 网络变化监听
│
├── proc/
│   ├── BinaryInstaller.kt        # 解包 gonc / tun2socks
│   ├── ProcessRunner.kt          # 进程启动/日志
│   ├── GoncManager.kt            # gonc 生命周期管理
│   └── Tun2SocksManager.kt       # tun2socks 生命周期管理
│
├── data/
│   ├── SettingsStore.kt          # secretKey / 路由配置
│   └── HostsStore.kt             # hosts 映射（App 内）
│
├── ui/
│   └── MainActivity.kt           # 开关 / 网段编辑 / 日志
│
└── util/
    └── CidrValidator.kt
```

---

## 6. VPN 接管策略

### 路由策略

* **仅接管以下网段（可配置）：**

  * `10.0.0.0/8`
  * `172.16.0.0/12`
  * `192.168.0.0/16`

### VpnService 行为约束

* ✅ `addRoute(cidr)`
* ❌ 不调用 `addDnsServer`
* ❌ 不接管 `0.0.0.0/0`
* ❌ 不修改系统 DNS

---

## 7. 启动顺序（强制）

```
START
  ↓
启动 gonc
  ↓ (等待 127.0.0.1:1080 可用)
建立 VPN TUN
  ↓
启动 tun2socks（绑定 TUN fd）
  ↓
RUNNING
```

### 任一模块异常

* 立即停止 VPN
* 杀掉 gonc / tun2socks
* 按策略重启（指数退避）

---

## 8. UDP 支持说明（关键）

### UDP 转发路径

```
App UDP
 → VpnService
 → TUN
 → tun2socks
 → SOCKS5 UDP ASSOCIATE
 → gonc
 → P2P 隧道
 → 路由器 gonc
 → 内网 UDP 服务
```

### 关键要求

* tun2socks **必须支持 SOCKS5 UDP ASSOCIATE**
* tun2socks 或 gonc 上游 socket **必须调用 `VpnService.protect()`**
* 不保证 UDP 实时性（UDP over P2P）

---

## 9. Hosts 支持策略

* 不修改系统 `/etc/hosts`
* hosts 仅用于：

  * App 内工具
  * 测试/记录用途
* MVP 阶段：

  * 推荐用户使用 **IP 直接访问内网**
  * hosts 作为增强功能后置

---

## 10. 安全设计（基础）

* `SECRET_KEY` 使用高熵随机字符串
* 不在日志中打印明文 key
* App 内存储使用加密（如 EncryptedDataStore）
* 路由器 ACL 可后续补充

---

## 11. MVP 验证清单（必须通过）

### TCP

* [ ] 浏览器访问 `http://192.168.x.1`
* [ ] SSH / HTTP / Web 管理页

### UDP

* [ ] 内网 UDP Echo 服务
* [ ] UDP 心跳 / 游戏服务器（如有）

### 稳定性

* [ ] Wi-Fi ↔ 蜂窝切换
* [ ] 锁屏 10 分钟不断连
* [ ] 后台运行不被杀（前台通知）

---

## 12. 暂不考虑

* ❌ 系统级 DNS 劫持
* ❌ ICMP / ping
* ❌ 局域网广播
* ❌ 公网全局代理

---

## 13. 后续扩展方向（可选）

* 多 secretKey / 多网关

---

## 14. 一句话总结

> **这是一个“Android 分流 VPN + P2P 内网访问”的工程方案，
> Android 只负责接管指定流量，
> gonc 负责穿透与安全通道，
> tun2socks 负责 TCP/UDP 转发。**

---

