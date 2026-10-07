// Prints the milliseconds the patched Dolphin compares Minecraft's heartbeat with
// (GalaxyCraft.cpp's NowMs: steady_clock), for tools/ci/ClockCheck.java.
#include <chrono>
#include <cstdio>

int main()
{
  const auto ms = std::chrono::duration_cast<std::chrono::milliseconds>(
      std::chrono::steady_clock::now().time_since_epoch());
  std::printf("%lld\n", static_cast<long long>(ms.count()));
  return 0;
}
