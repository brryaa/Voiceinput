# Voiceinput (Lvoice) - 智能混合型语音辅助输入法

**Voiceinput** 是一款专为极客和效率追求者打造的 Android **“隐形辅助输入法”**。它摒弃了传统的屏幕键盘 UI，专注于提供极致的语音输入体验。

本项目最大的亮点在于其首创的 **“混合双驱语音识别架构 (Hybrid STT)”**：它将 Android 原生底层的极速响应，与 Google Gemini 超大模型的碾压级语义理解能力完美结合，实现了**“毫秒级上屏，秒级AI纠偏”**的科幻级输入体验。

---

## 🌟 核心理念与痛点解决

在传统的安卓语音输入方案中，用户往往面临“鱼与熊掌不可兼得”的困境：
- **方案 A (纯原生引擎)**：比如调用系统自带的 SpeechRecognizer。优点是完全离线或延迟极低（边说边上屏），但缺点是对同音字、口音、专有名词的识别率惨不忍睹，经常输出令人啼笑皆非的“文盲句子”。
- **方案 B (纯云端大模型)**：比如直接录一段音传给 Whisper 或 Gemini。优点是精准得令人发指，甚至能加上完美的标点符号。但缺点是**反馈太慢**，用户说完话要盯着屏幕干等 2~3 秒，文字才突然蹦出来，输入体验极度割裂。

**Voiceinput 的目标，就是彻底打破这个物理定律。**

---

## 🚀 核心特性：混合双驱引擎 (Hybrid STT Engine)

本项目采用创新的双管道处理流，让极速流和高精流并行运作：

1. **瞬时草稿流 (Draft Stream)**
   当您开始说话时，Voiceinput 会实时调用系统原生的 `SpeechRecognizer`。由于是底层原生服务，您**每说一个字，屏幕上就会立刻出现一个字**（草稿状态）。这种零延迟的视觉反馈会让大脑感到极度舒适和安心。

2. **静默精修流 (Refinement Stream)**
   在底层引擎工作的同时，Voiceinput 正在用极高的采样率将您的原始未压缩音频 (WAV) 缓存到本地。一旦您停顿（仅需 800 毫秒），音频便会通过后台微秒级的调度，立刻发送至 `models/gemini-flash-latest` (Google 当前最快的多模态模型)。

3. **魔术替换 (Magic Injection)**
   Gemini 拿到音频后，会参考之前的“草稿文字”进行顶级的 AI 听写校对。此时您可能已经切回了之前的键盘看到了那句含有错别字的草稿，但紧接着（通常就在 1 秒之内），**屏幕上的错别字会瞬间发生变化，犹如魔术一般被替换成了带标点的、语法完美的终极版本。**

---

## ⚙️ 极致的输入法状态管理

作为一个辅助型输入法，如何优雅地“呼叫来，挥之去”，是体验的重中之重。

### 1. 隐形辅助键盘架构 (Auxiliary IME)
本应用配置了 `android:isAuxiliary="true"`，这意味着它**不会出现在系统默认的输入法列表中**。
它的正确用法是：当您正在使用 Gboard 或其他主输入法时，长按“地球键”或“空格键”（取决于主输入法），在弹出的快捷切换菜单中选择 "Lvoice"。它不会弹出任何挡住屏幕的键盘面板，而是直接进入听写状态。

### 2. 800ms 黄金静音超时原则
为了做到“说完就走”，我们抛弃了传统语音输入法动辄 2-3 秒的等待期。在查阅大量语料库和反复人肉测试后，我们将自动退出的静音判断阈值（Silence Timeout）锁死在 **800ms**。
- **400ms 停顿**：触发连续句断句，自动加上逗号或句号，继续聆听。
- **800ms 彻底无声**：瞬间斩断连接，触发 AI 纠错流水线，并自动帮您在底层切换回您**上一次使用的那个输入法**（如 Gboard）。

### 3. 硬核抗噪超时器 (Noise-Immune Timeout)
在嘈杂环境下（如街边车流、餐厅背景音），底层的 VAD（人声检测）经常失效，它会认为“一直有声音”，导致输入法永远不退出。
为了解决这个痛点，我们手写了一个绝对时间的 `noiseTimeoutRunnable`。**不管环境多吵，只要系统在 800ms 内没有输出任何有意义的文字（Partial Results），系统就会凭借绝对的权威强制判定超时并退出。** 这彻底解决了“语音键盘退不回去需要手动点”的恶心体验。

### 4. 生命周期防穿透与自恢复
- **GlobalScope 守护**：即使 800ms 后输入法已经被系统无情销毁并切回 Gboard，AI 的精修请求依然会在 `GlobalScope` 或 `NonCancellable` 上下文中执着地跑完，并把正确的字“隔山打牛”般注回屏幕。
- **Error 11 免疫**：深度屏蔽了 Android 平台上臭名昭著的 `ERROR_LANGUAGE_NOT_SUPPORTED (11)` 间歇性网络断连报错，通过微秒级的静默重启（postDelayed restart）让用户彻底无感。

---

## 🛠️ 技术栈与模块解析

- **语言**: 100% Kotlin 构筑
- **UI 框架**: Jetpack Compose (仅用于少量设置页和鉴权反馈)
- **依赖库**:
  - `androidx.credentials`: 用于现代化的 Google 账号单点登录 (OAuth)
  - `google.generativeai`: 官方 Gemini SDK（配合底层的 REST API 直接调用，绕过部分地区限制）
  - `androidx.datastore`: 用于安全、异步地存储 API Key。
  - `kotlinx.coroutines`: 整个混合引擎的心脏，主导了异步听写、超时判定、和网络请求的协程调度。

### 核心类一览
- `LvoiceIME.kt`: 整个逻辑的指挥塔，继承自 `InputMethodService`。负责协调系统输入连接 (InputConnection) 和状态机，也是“草稿替换魔法”的施法者。
- `VoiceInputManager.kt`: 对底层 `SpeechRecognizer` 和 `AudioRecord` 的超级封装器。包含了 800ms 杂音强制退出、无限重启循环阻断等极其硬核的降级容错代码。
- `GeminiVoiceClient.kt`: RESTful 的 Gemini 直连客户端。内含针对语音转写极其苛刻的 Prompt 工程（`temperature: 0.0`），确保 AI 像听写员一样工作而不是去发散闲聊。
- `ModelSniffer.kt`: 内置的诊断工具，使用 OAuth 或 API Key 对接 Google 服务器，为您穷举当前账号真实可用的 Gemini 模型矩阵。

---

## 📦 如何安装与配置

### 1. 获取 API Key
体验高精修功能需要您自备 Google Gemini API Key。
前提：请确保您所在地区的网络可以合法访问 Google API 服务。您可以在 [Google AI Studio](https://aistudio.google.com/) 免费申请。

### 2. 编译与安装
```bash
# 由于是辅助型输入法，您无法在 Launcher 桌面直接看到它的启动图标
./gradlew assembleDebug
adb install app/build/outputs/apk/debug/app-debug.apk
```

### 3. 配置输入法
1. 安装完成后，前往 Android 的 **系统设置** -> **语言与输入法** -> **虚拟键盘** -> **管理键盘**。
2. 找到 `Lvoice (Voiceinput)`，拨动开关启用它。由于是基于 AI 的应用，系统会弹出标准的“收集所有文本”安全警告，点击确认即可（所有的音频流都仅在您说话后直连 Google Gemini 服务器，本项目无任何私设的中间转发服务器）。
3. 点击应用列表中的 `Lvoice 设置` App 补齐 API Key。
4. 呼出您现在的键盘（例如 Gboard），长按地球键，选择 `Lvoice`。立刻开始您顺滑丝滑的输入之旅吧！

---

## 📝 TODO 与未来展望

- [ ] 支持自定义 Prompt，让用户决定是“转写”还是“翻译”。
- [ ] 根据网络环境，动态降级：没网时优雅地只使用纯原生流。
- [ ] 加入本地唤醒词（Wakeword）集成，彻底解放双手。

--- 
*Created by Justin Lau*
