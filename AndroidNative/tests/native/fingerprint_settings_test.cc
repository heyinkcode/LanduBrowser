#include "../../native/landu_fingerprint_settings.h"

#include <iostream>
#include <stdexcept>

int main() {
  unsigned assertions = 0;
  auto check = [&](bool value) {
    ++assertions;
    if (!value)
      throw std::runtime_error("Native fingerprint setting validation failed");
  };
  for (auto value : {"2", "4", "8", "12", "16"})
    check(blink::landu::HardwareConcurrency(value).has_value());
  for (auto value : {"1", "2", "4", "8"})
    check(blink::landu::DeviceMemory(value).has_value());
  for (auto invalid : {"", "0", "01", "-2", "+4", " 4", "4 ", "4.0",
                       "4\n", "4294967295", "32", "2x"}) {
    check(!blink::landu::HardwareConcurrency(invalid).has_value());
    check(!blink::landu::DeviceMemory(invalid).has_value());
  }
  check(blink::landu::HardwareConcurrency("12").value() == 12);
  check(blink::landu::DeviceMemory("4").value() == 4.0f);
  check(!blink::landu::DeviceMemory("16").has_value());
  for (auto blocked : {"Microsoft YaHei", "MICROSOFT-JHENGHEI UI", "@SimSun",
                       "SimSun-ExtB", "NSimSun", "SimHei", "FangSong_GB2312",
                       "KaiTi", "PingFangSC-Regular", "STHeitiSC-Light",
                       "Noto Sans CJK SC", "NotoSerifTC", "SourceHanSansCN-Bold",
                       "Droid Sans Fallback", "MiSans", "MiSansVF",
                       "mi_lanting", "HarmonyOS Sans SC", "OPPO Sans",
                       "vivo Sans", "HONOR Sans", "微软雅黑", "微軟正黑體",
                       "宋体", "黑體", "苹方-简", "思源黑体 CN",
                       "华文宋体", "小米兰亭 Pro"}) {
    check(blink::landu::IsRegionalLocalFont(blocked));
  }
  for (auto allowed : {"", "sans-serif", "serif", "monospace", "system-ui",
                       "Roboto", "Roboto Mono", "Arial", "Times New Roman",
                       "Noto Sans", "Noto Sans Arabic", "Noto Color Emoji",
                       "Simple Sans", "CustomWebFont", "Segoe UI"}) {
    check(!blink::landu::IsRegionalLocalFont(allowed));
  }
  check(!blink::landu::IsRegionalLocalFont(std::string(1024, 'a')));
  check(blink::landu::IsRegionalLocalFont(std::string(1024, ' ') + "MiSans"));
  check(blink::landu::IsRegionalLocalFont("MiSans" + std::string(1024, ' ')));
  std::cout << "{\"suite\":\"native-fingerprint-settings\",\"assertions\":"
            << assertions << ",\"passed\":true,\"browserRuntimeTested\":false}\n";
}
