cmake_minimum_required(VERSION 3.22)
project(mishka)

add_library(mishka SHARED process_helper.c)
target_link_libraries(mishka log)

# 体积优化
target_compile_options(mishka PRIVATE -Os -fvisibility=hidden -ffunction-sections -fdata-sections)
target_link_options(mishka PRIVATE -Wl,--gc-sections -Wl,--strip-all -Wl,--exclude-libs,ALL)
