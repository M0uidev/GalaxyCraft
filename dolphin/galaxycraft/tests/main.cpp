#include "TestRunner.h"

int main()
{
  int failed_tests = 0;
  for (auto& [name, fn] : gxc_test::Registry::Tests())
  {
    const int before = gxc_test::Failures();
    fn();
    const bool ok = gxc_test::Failures() == before;
    failed_tests += ok ? 0 : 1;
    std::printf("%s %s\n", ok ? "ok  " : "FAIL", name.c_str());
  }
  std::printf("%zu tests, %d failed\n", gxc_test::Registry::Tests().size(), failed_tests);
  return failed_tests == 0 ? 0 : 1;
}
