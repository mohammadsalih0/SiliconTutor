cmake_minimum_required(VERSION 3.20)
project(silicontutor VERSION 1.0.0 LANGUAGES CXX)
include(CTest)

set(CMAKE_CXX_STANDARD 20)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_CXX_EXTENSIONS OFF)

find_package(PkgConfig REQUIRED)
pkg_check_modules(GTK4 REQUIRED IMPORTED_TARGET gtk4>=4.10)
pkg_check_modules(ADWAITA REQUIRED IMPORTED_TARGET libadwaita-1>=1.4)
pkg_check_modules(SQLITE REQUIRED IMPORTED_TARGET sqlite3)
pkg_check_modules(JSON_GLIB REQUIRED IMPORTED_TARGET json-glib-1.0)
pkg_check_modules(RSVG REQUIRED IMPORTED_TARGET librsvg-2.0)

add_executable(silicontutor runner/main.cc)
target_include_directories(silicontutor PRIVATE tools)
target_compile_definitions(silicontutor PRIVATE
  SILICONTUTOR_SOURCE_CURRICULUM_DIR="${CMAKE_CURRENT_SOURCE_DIR}/../curriculum"
)
target_link_libraries(silicontutor PRIVATE
  PkgConfig::GTK4
  PkgConfig::ADWAITA
  PkgConfig::SQLITE
  PkgConfig::JSON_GLIB
  PkgConfig::RSVG
)
target_compile_options(silicontutor PRIVATE -Wall -Wextra -Wpedantic)

add_executable(silicontutor-uart-cosim tools/uart_cosim.cpp)
target_include_directories(silicontutor-uart-cosim PRIVATE tools)
target_compile_definitions(silicontutor-uart-cosim PRIVATE
  SILICONTUTOR_UART_HARNESS_PATH="${CMAKE_CURRENT_SOURCE_DIR}/tools/uart_sim_harness.cpp"
)
target_compile_options(silicontutor-uart-cosim PRIVATE -Wall -Wextra -Wpedantic)

if(BUILD_TESTING)
  add_executable(silicontutor-data-tests EXCLUDE_FROM_ALL tests/data_test.cc)
  target_link_libraries(silicontutor-data-tests PRIVATE
    PkgConfig::GTK4
    PkgConfig::ADWAITA
    PkgConfig::SQLITE
    PkgConfig::JSON_GLIB
    PkgConfig::RSVG
  )
  target_compile_options(silicontutor-data-tests PRIVATE -Wall -Wextra -Wpedantic)
  target_include_directories(silicontutor-data-tests PRIVATE tools)
  target_compile_definitions(silicontutor-data-tests PRIVATE
    SILICONTUTOR_SOURCE_CURRICULUM_DIR="${CMAKE_CURRENT_SOURCE_DIR}/../curriculum"
  )
  add_test(NAME native-material-data COMMAND silicontutor-data-tests)
endif()

install(TARGETS silicontutor RUNTIME DESTINATION bin)
install(TARGETS silicontutor-uart-cosim RUNTIME DESTINATION bin)
install(FILES tools/uart_sim_harness.cpp tools/uart_protocol.hpp
  DESTINATION share/silicontutor)
install(FILES
  ../curriculum/beginner.json
  ../curriculum/intermediate.json
  ../curriculum/expert.json
  DESTINATION share/silicontutor/curriculum)
install(FILES packaging/silicontutor.desktop DESTINATION share/applications)
install(FILES ../assets/icons/silicontutor.svg
  DESTINATION share/icons/hicolor/scalable/apps)
