from pypdf import PdfReader

path = "/Users/linxun/Claude Code Development/ai-guided/vendor/qingcheng-glasses-sdk/SDK使用说明.pdf"
terms = (
    "enableDeviceBT",
    "DeviceBT",
    "蓝牙",
    "配对",
    "enableWifi",
    "connectWifi",
    "downloadMediaFile",
    "WiFi",
    "Wifi",
    "媒体",
    "文件下载",
    "重连",
    "AI对话",
    "录音",
)

reader = PdfReader(path)
for page_number, page in enumerate(reader.pages, 1):
    text = page.extract_text() or ""
    if any(term in text for term in terms):
        print(f"===== PAGE {page_number} =====")
        print(text)
