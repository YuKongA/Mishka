cmake_minimum_required(VERSION 3.22)
project(mishka)

add_library(mishka SHARED process_helper.c)
target_link_libraries(mishka log)
