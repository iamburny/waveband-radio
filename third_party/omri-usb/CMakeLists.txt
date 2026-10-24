# Builds the omri-usb DAB stack as libomri.so (LGPL-2.1, dynamically linked by the app)
# plus upstream's Reed-Solomon helper libfec.so.

cmake_minimum_required(VERSION 3.18)

set(OMRI_UPSTREAM_COMMIT "12f4b8ede51509226da102952646b97cb3c5752f")
set(OMRI_SRC ${CMAKE_CURRENT_LIST_DIR}/src)

add_subdirectory(${OMRI_SRC}/thirdparty/fec ${CMAKE_CURRENT_BINARY_DIR}/fec)

add_library(omri SHARED
        ${OMRI_SRC}/fig_00_ext_00.cpp
        ${OMRI_SRC}/fig_00_ext_01.cpp
        ${OMRI_SRC}/fig_00_ext_02.cpp
        ${OMRI_SRC}/fig_00_ext_03.cpp
        ${OMRI_SRC}/fig_00_ext_04.cpp
        ${OMRI_SRC}/fig_00_ext_05.cpp
        ${OMRI_SRC}/fig_00_ext_06.cpp
        ${OMRI_SRC}/fig_00_ext_07.cpp
        ${OMRI_SRC}/fig_00_ext_08.cpp
        ${OMRI_SRC}/fig_00_ext_09.cpp
        ${OMRI_SRC}/fig_00_ext_10.cpp
        ${OMRI_SRC}/fig_00_ext_13.cpp
        ${OMRI_SRC}/fig_00_ext_14.cpp
        ${OMRI_SRC}/fig_00_ext_17.cpp
        ${OMRI_SRC}/fig_00_ext_18.cpp
        ${OMRI_SRC}/fig_00_ext_19.cpp
        ${OMRI_SRC}/fig_00_ext_20.cpp
        ${OMRI_SRC}/fig_00_ext_21.cpp
        ${OMRI_SRC}/fig_00_ext_24.cpp
        ${OMRI_SRC}/fig_00_ext_25.cpp
        ${OMRI_SRC}/fig_00_ext_26.cpp
        ${OMRI_SRC}/fig_01_ext_00.cpp
        ${OMRI_SRC}/fig_01_ext_01.cpp
        ${OMRI_SRC}/fig_01_ext_04.cpp
        ${OMRI_SRC}/fig_01_ext_05.cpp
        ${OMRI_SRC}/fig_01_ext_06.cpp
        ${OMRI_SRC}/dabensemble.cpp
        ${OMRI_SRC}/dabmotuserapplicationdecoder.cpp
        ${OMRI_SRC}/dabmpegservicecomponentdecoder.cpp
        ${OMRI_SRC}/dabplusservicecomponentdecoder.cpp
        ${OMRI_SRC}/dabservice.cpp
        ${OMRI_SRC}/dabservicecomponent.cpp
        ${OMRI_SRC}/dabservicecomponentdecoder.cpp
        ${OMRI_SRC}/dabservicecomponentmscpacketdata.cpp
        ${OMRI_SRC}/dabservicecomponentmscstream.cpp
        ${OMRI_SRC}/dabservicecomponentmscstreamaudio.cpp
        ${OMRI_SRC}/dabservicecomponentmscstreamdata.cpp
        ${OMRI_SRC}/dabuserapplication.cpp
        ${OMRI_SRC}/dabuserapplicationdecoder.cpp
        ${OMRI_SRC}/dynamiclabeldecoder.cpp
        ${OMRI_SRC}/ficparser.cpp
        ${OMRI_SRC}/motdecoder.cpp
        ${OMRI_SRC}/mscdatagroupdecoder.cpp
        ${OMRI_SRC}/paddecoder.cpp
        ${OMRI_SRC}/slideshowdecoder.cpp
        ${OMRI_SRC}/platformspecific/android/dabservicelink.cpp
        ${OMRI_SRC}/platformspecific/android/omri_api.cpp
        ${OMRI_SRC}/platformspecific/android/raontunerinput.cpp
        ${OMRI_SRC}/platformspecific/android/usbtunerdevice.cpp
        )

target_include_directories(omri
        PUBLIC ${OMRI_SRC}/platformspecific/android
        PRIVATE ${OMRI_SRC} ${OMRI_SRC}/thirdparty/fec)
target_compile_definitions(omri PRIVATE OMRI_UPSTREAM_COMMIT="${OMRI_UPSTREAM_COMMIT}")
target_compile_options(omri PRIVATE -frtti -fexceptions -fvisibility=hidden -Wno-everything)
set_target_properties(omri PROPERTIES CXX_STANDARD 14 CXX_STANDARD_REQUIRED ON)
target_link_libraries(omri PRIVATE fec log)
target_link_options(omri PRIVATE "-Wl,-z,max-page-size=16384")
target_link_options(fec PRIVATE "-Wl,-z,max-page-size=16384")
