# GB28181 模拟器架构说明（中文）

本文档用于梳理当前项目的中文架构视图，覆盖：

- 系统角色与模块分层
- SIP 信令与媒体（PS over RTP）主流程
- 三种启动入口与交互控制台关系
- 多客户端与命令体系设计
- 测试关注点与当前边界

> 说明：本项目定位为“联调/互通模拟器”，用于测试流程与互通行为，不是生产级国标平台或设备实现。

## 1. 系统目标与角色

### 1.1 目标

- 快速模拟 GB28181 平台端与设备端 SIP 交互。
- 支持 2016/2022 典型差异化字段与流程。
- 支持 UDP/TCP/TLS 三种 SIP 传输。
- 支持可观察、可脚本化、可交互的端到端联调测试。

### 1.2 角色划分

- **平台侧**：`Gb28181ServerSimulator`
  - 接收设备注册、心跳、响应消息。
  - 发起查询、控制、订阅、媒体会话（INVITE）。
- **设备侧**：`Gb28181ClientSimulator`
  - 发起注册与心跳。
  - 应答平台查询/控制/订阅/INVITE 请求。
  - 在媒体建立后发送模拟 PS/RTP 流。

## 2. 启动入口与运行模式

### 2.1 入口

- `MainKt`：双端合并入口（`all` / `fault` / `server` / `client`）
- `ServerMainKt`：仅平台端
- `ClientMainKt`：仅设备端

### 2.2 常见模式

- `all`：同进程启动平台+设备，适合本地联调。
- `fault`：同 `all`，但用于触发故障注入命令验证健壮性。
- `server`：只启平台，外接真实/其他模拟设备。
- `client`：只启设备，外接真实/其他模拟平台。

## 3. 核心模块分层

### 3.1 协议与传输层

- `SipMessage.kt`
  - SIP 编解码、头字段处理、多值头拆分等。
- `SipUtil.kt`
  - 分支、标签、Call-ID、Digest 相关工具。
- `SipTransportFactory.kt`
  - 根据配置创建 UDP/TCP/TLS 传输实现。
- `UdpSipTransport.kt` / `TcpSipTransport.kt` / `TlsSipTransport.kt`
  - 实际网络收发与连接管理。

### 3.2 业务模拟层

- `Gb28181ServerSimulator.kt`
  - 注册鉴权、设备表维护、查询控制订阅、INVITE/BYE 管理。
- `Gb28181ClientSimulator.kt`
  - 注册与心跳、查询/控制响应、INVITE 应答、媒体推流控制。

### 3.3 媒体模拟层

- `SimulatedPsRtpStreamer.kt`
  - 周期发送 RTP 包并统计发送信息（包数/字节/时长）。
- `PsSimulator.kt`
  - 构造模拟 PS 负载。
- `RtpPacket.kt`
  - RTP 包头与封包。

### 3.4 配置与日志层

- `Config.kt`
  - 版本、传输、设备/平台配置、厂商策略配置结构。
- `configs/*.json`
  - 厂商策略覆盖样例（海康/大华/宇视等）。
- `Logger.kt`
  - 结构化日志与交互提示兼容输出。

## 4. 关键信令流程

## 4.1 注册鉴权

典型流程：

1. `REGISTER`
2. `401 Unauthorized`（带 `WWW-Authenticate`）
3. `REGISTER + Authorization`
4. `200 OK`

补充行为：

- 注销：`REGISTER` + `Expires: 0`
- 服务端移除设备注册映射
- 客户端本地状态切换为未注册

## 4.2 业务消息（MESSAGE + MANSCDP）

- 平台发起 `q-*` / `c-*`
- 设备先 `200 OK`，再回 `MESSAGE(Response XML)`
- 平台收到后记录 `query_response_received` 等日志

## 4.3 订阅通知（SUBSCRIBE/NOTIFY）

- 平台发 `SUBSCRIBE`
- 设备回 `200`
- 设备主动发 `NOTIFY`
- 平台回 `200`

## 4.4 媒体会话（INVITE/ACK/BYE）

- 建立：`INVITE -> 200 -> ACK`
- 释放：`BYE -> 200`
- 支持场景：
  - 实时：`i-play`
  - 回放：`i-playback`
  - 下载：`i-download`
  - 对讲：`i-talk`

## 5. 媒体路径（PS over RTP）

设备侧逻辑：

1. 从 INVITE SDP 提取目标 IP/端口（`c=`、`m=video`）
2. 创建 `SimulatedPsRtpStreamer`
3. 构造 PS 负载并封装 RTP
4. UDP 连续发送到协商目标
5. 收到会话停止（`BYE`）后停止发送并清理状态

可观察性：

- 输出 `ps_rtp_stream_start/progress/stop` 类日志
- `status` 可查看流计数与统计

## 6. 交互命令体系（当前规范）

命令风格统一为“分级动作”：

- `client ...`
- `channel ...`
- `i-xxx start/stop ...`

### 6.1 典型命令组

- `client new/list/use/register/keepalive/unregister/fault`
- `client keepalive loop/stop`
- `channel list/set`
- `q-*`、`c-*`、`s-*`
- `i-play|i-playback|i-download|i-talk start/stop`

### 6.2 多客户端模型

- 通过 `client new` 动态创建客户端实例
- 每个实例分配 `clientId`（如 `client-1`、`client-2`）
- `client use <clientId>` 切换当前激活客户端
- 服务端业务命令首参支持 `deviceId|clientId` 映射（合并模式）

## 7. 数据状态与运行时对象

### 7.1 服务端主要状态

- 已注册设备映射（`deviceId -> SipEndpoint`）
- 心跳时间戳表
- 订阅表
- INVITE 对话表
- 事务缓存（重传响应）
- 设备通道列表

### 7.2 客户端主要状态

- 注册状态（registered）
- 鉴权 nonce 状态
- 手动心跳 loop 任务状态
- 入向 INVITE 事务状态
- 活动媒体推流器列表
- 事务缓存（重传响应）

## 8. 鲁棒性与互通增强点（已实现）

- REGISTER Digest 鉴权与 stale nonce 处理
- SIP 头折叠、多值头安全拆分、紧凑头支持
- INVITE/CANCEL/PRACK/OPTIONS 典型互通流程
- 基础 CSeq 顺序检查、事务重传缓存
- TCP 断连重试与长连接心跳
- 心跳超时下线清理

## 9. 端口与网络默认值

- 平台 SIP：`5060`
- 设备 SIP：`5061`（新增客户端按序递增）
- 媒体 RTP：命令可指定（常用示例 `15060+`）

## 10. 建议测试编排（中文）

### 10.1 快速冒烟

1. `client register`
2. `q-info`
3. `i-play start`
4. `i-play stop`

### 10.2 功能回归

- 查询：`q-info/q-status/q-record/q-alarm/q-cfg-*`
- 控制：`c-ptz/c-zoom/c-home/c-reboot`
- 订阅：`s-alarm/s-mobile/s-catalog`
- 媒体：四类 `start/stop`

### 10.3 稳定性

- `client keepalive loop 10`
- 长跑观察是否出现超时下线与状态不一致

## 11. 当前边界与后续可扩展方向

### 11.1 当前边界

- 媒体为“模拟 PS/RTP”，不覆盖真实编码链路。
- 主要面向联调验证，不覆盖所有生产级高并发场景。

### 11.2 后续方向

- 更完整 MANSCDP 命令覆盖
- 媒体接收与质量统计增强
- 更细粒度的事务/对话一致性校验
- 长时压测与故障注入脚本体系增强

