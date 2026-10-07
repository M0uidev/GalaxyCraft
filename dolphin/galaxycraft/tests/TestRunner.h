#pragma once
// Minimal test runner: TEST(name) { CHECK(cond); }
#include <cstdio>
#include <filesystem>
#include <functional>
#include <string>
#include <vector>
#ifdef _WIN32
#include <process.h>
#else
#include <unistd.h>
#endif

namespace gxc_test
{
struct Registry
{
  static std::vector<std::pair<std::string, std::function<void()>>>& Tests()
  {
    static std::vector<std::pair<std::string, std::function<void()>>> tests;
    return tests;
  }
};
struct Register
{
  Register(const char* name, std::function<void()> fn) { Registry::Tests().emplace_back(name, fn); }
};
// A file of this test process in the system's temp folder (/tmp, %TEMP%).
inline std::string TempFile(const std::string& name)
{
#ifdef _WIN32
  const int pid = _getpid();
#else
  const int pid = getpid();
#endif
  return (std::filesystem::temp_directory_path() / (name + std::to_string(pid))).string();
}

inline int& Failures()
{
  static int failures = 0;
  return failures;
}
}  // namespace gxc_test

#define TEST(name)                                                                                 \
  static void name();                                                                              \
  static gxc_test::Register name##_reg(#name, name);                                               \
  static void name()

#define CHECK(cond)                                                                                \
  do                                                                                               \
  {                                                                                                \
    if (!(cond))                                                                                   \
    {                                                                                              \
      std::printf("  FAILED %s:%d: %s\n", __FILE__, __LINE__, #cond);                              \
      ++gxc_test::Failures();                                                                      \
      return;                                                                                      \
    }                                                                                              \
  } while (0)
