cmake_minimum_required(VERSION 3.20)
project(galaxycraft_host CXX)

# Host side of the GalaxyCraft bridge. Free of Dolphin dependencies so it can be tested on its
# own; Dolphin pulls it in with add_subdirectory (see dolphin/build.sh).
if(NOT CMAKE_CXX_STANDARD)
  set(CMAKE_CXX_STANDARD 20)
  set(CMAKE_CXX_STANDARD_REQUIRED ON)
endif()

add_library(galaxycraft_host STATIC Shm.cpp HostBridge.cpp Overlay.cpp Input.cpp DevControl.cpp MarioInput.cpp
  MarioSkin.cpp)
target_include_directories(galaxycraft_host PUBLIC
  ${CMAKE_CURRENT_SOURCE_DIR}
  ${CMAKE_CURRENT_SOURCE_DIR}/../../protocol)

if(MSVC)
  set(GALAXYCRAFT_WARNINGS /W4 /WX /permissive- /utf-8)
else()
  set(GALAXYCRAFT_WARNINGS -Wall -Wextra -Werror)
endif()

if(PROJECT_IS_TOP_LEVEL)
  target_compile_options(galaxycraft_host PRIVATE ${GALAXYCRAFT_WARNINGS})
  add_executable(galaxycraft_host_tests tests/main.cpp tests/host_bridge_test.cpp
    tests/overlay_input_test.cpp tests/devcontrol_test.cpp tests/mario_input_test.cpp
    tests/mario_skin_test.cpp)
  target_link_libraries(galaxycraft_host_tests PRIVATE galaxycraft_host)
  target_compile_options(galaxycraft_host_tests PRIVATE ${GALAXYCRAFT_WARNINGS})
endif()

if(PROJECT_IS_TOP_LEVEL)
  # tools/ci/ClockCheck.java: Java's System.nanoTime and this steady_clock are the same clock.
  add_executable(clock_probe tests/clock_probe.cpp)
endif()
