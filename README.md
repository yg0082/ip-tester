# IP批量测试（自用安卓 App）

功能：批量输入 IP，每行一个，测 ping 延迟、443 TCP 建连耗时、HTTPS 下载速度、IP 归属地（ip-api），按延迟从低到高排序显示。

## 手机上安装（不用电脑）
1. 把本仓库代码推到 GitHub 后，打开仓库的 Actions 页，等 Build APK 跑完。
2. 在 Artifacts 里下载 app-debug-apk，解压得到 app-debug.apk，传到手机。
3. 手机上点开 APK，允许“安装未知应用”后安装，打开 App 即可用。
4. 粘贴 IP 列表，点“开始测试”。注意：下载速度那一列测的是对该 IP 的 HTTPS 响应速度，有的 IP 不提供网页服务会显示“-”，以延迟和 TCP 为主看。
