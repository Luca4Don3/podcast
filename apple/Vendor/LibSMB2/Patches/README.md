# libsmb2 本地移植补丁

上游来源：sahlberg/libsmb2，LGPL-2.1-or-later；完整许可证在 Upstream。

1. 将 Upstream/include/smb2/smb2.h 内两条 `#include <smb2/...>` 改为同目录的引号相对包含，使 SwiftPM 生成的 Clang module 能在仅公开 module 目录时解析嵌套头。
2. 将 `Upstream/include/apple/config.h` 复制到 `Upstream/lib/config.h`，使 C 源文件的引号包含可在自身目录解析。

补丁仅改变头文件查找方式，不改变 SMB 协议、认证或加密逻辑。原始上游文件仍保留许可证与通知。
