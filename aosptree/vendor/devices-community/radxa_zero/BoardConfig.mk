# SPDX-License-Identifier: Apache-2.0
#
# Copyright (C) 2023 The GloDroid project

BC_PATH := $(patsubst $(CURDIR)/%,%,$(dir $(abspath $(lastword $(MAKEFILE_LIST)))))

include glodroid/configuration/common/board-common.mk

BOARD_MESA3D_GALLIUM_DRIVERS := panfrost

BOARD_KERNEL_SRC_DIR := glodroid/kernel/common-android14-6.1-lts

# FIXME = Remove prebuilt binaries with broken elf
BUILD_BROKEN_ELF_PREBUILT_PRODUCT_COPY_FILES := true

