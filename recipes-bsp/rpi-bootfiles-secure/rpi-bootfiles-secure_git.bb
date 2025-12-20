SUMMARY = "Create RSA-secured boot-image with Raspberry Pi x's shell-scripts"
DESCRIPTION = "This repository contains the rpi4/rpi5 USB tools \
e.g. for creating signed boot image (boot.img & boot.sig)"
LICENSE = "Broadcom-RPi"
LIC_FILES_CHKSUM = "file://LICENSE;md5=e3fc50a88d0a364313df4b21ef20c29e"

inherit deploy nopackages

SRC_URI = " \
    git://github.com/raspberrypi/usbboot.git;protocol=https;branch=pios/bookworm \
"

SRCREV = "df27f13707de24b7059fee180f4cefe5ae90ee81"
PV = "20250908-162618-bookworm"

COMPATIBLE_MACHINE = "^rpi$"

S = "${WORKDIR}/git"

DEPENDS = "rpi-bootfiles"

RDEPENDS:${pkgvarcheck} += " \
    e2fsprogs-mke2fs \
"

# rpi-eeprom-digest is a tool for signing boot.img file.
# It's located in submodule (submodule rpi_eeprom.git for usbboot.git),
# so lets fetch also the submodule.
do_configure() {
  cd ${S}
  git submodule update --init --recursive
}

do_install() {
    install -d ${D}${bindir}

    # install executables (well, 'boot-image'-maker and 'secure signing'-tool
    # most likely not even needed in RPI-target...)
    install -m 0755 ${S}/tools/rpi-make-boot-image ${D}${bindir}
    install -m 0755 ${S}/rpi-eeprom/rpi-eeprom-digest ${D}${bindir}
}

do_deploy() {
    install -d ${DEPLOYDIR}/${BOOTFILES_DIR_NAME}
    install -d ${STAGING_DIR_TARGET}/${BOOTFILES_DIR_NAME}
    install -d ${STAGING_DIR_TARGET}/${BOOTFILES_DIR_NAME}/overlays
    install -d ${STAGING_DIR_TARGET}/eeprom_bootloader

    # -------------------------------------------------------------------------------
    #               Secure eeprom-bootloader

    # Sign boot-config with private RSA-key 
    # This config (/secure-boot-recovery/boot.conf) contains flag 'SIGNED_BOOT=1',
    # but no OTP-settings, so it's still possible to roll back to non-secure world!

    ${S}/rpi-eeprom/rpi-eeprom-digest \
    -k ${TOPDIR}/conf/private.pem \
    -i ${S}/secure-boot-recovery/boot.conf \
    -o ${STAGING_DIR_TARGET}/eeprom_bootloader/boot.conf.sig    

    # Create secure eeprom-bootloader

    ${S}/rpi-eeprom/rpi-eeprom-config \
    -p ${TOPDIR}/conf/public.pub \
    -c ${S}/secure-boot-recovery/boot.conf \
    -d ${STAGING_DIR_TARGET}/eeprom_bootloader/boot.conf.sig \
    -o ${STAGING_DIR_TARGET}/eeprom_bootloader/pieeprom_secure.bin \
       ${S}/rpi-eeprom/firmware-2711/latest/pieeprom-2025-08-27.bin

   #  Signature-file (SHA256 checksum) for eeprom-bootloader  

    ${S}/rpi-eeprom/rpi-eeprom-digest \
    -i ${STAGING_DIR_TARGET}/eeprom_bootloader/pieeprom_secure.bin \
    -o ${STAGING_DIR_TARGET}/eeprom_bootloader/pieeprom_secure.sig


    # Deploy recovery.bin, secured eeprom-bootloader and signature:

    # recovery.bin on SD-card's boot-partition triggers the update-process of eeprom-bootloader  
    cp ${S}/rpi-eeprom/firmware-2711/latest/recovery.bin ${DEPLOYDIR}

    # If pieeprom.bin is renamed as pieeprom.upd, recovery.bin is renamed to RECOVERY.000
    # after succeeded flashing of new eeprom-bootloader.
    # This avoids the bootloader to be flashed again and again after next boot-ups!
    # I'll still keep here 'pieeprom.bin' (and rename manually recovery.bin on SD-card),
    # because this way RaspberryPI's leds (green/red) indicate the progress of the flashing.

    cp ${STAGING_DIR_TARGET}/eeprom_bootloader/pieeprom_secure.bin ${DEPLOYDIR}/pieeprom.bin

    cp ${STAGING_DIR_TARGET}/eeprom_bootloader/pieeprom_secure.sig ${DEPLOYDIR}/pieeprom.sig

    # -------------------------------------------------------------------------------
    
    # Secure eeprom-bootloader is now deployed.
    # Gather other boot-files (start.elf-bootloader, kernel, device trees etc.) from DEPLOY-folders 
    # to staging-dir, store those files to boot.img. Create boot.sig. Finally deploy boot.img and boot.sig.  

    # start.elf, fixup.dat etc.
    for i in ${DEPLOY_DIR_IMAGE}/${BOOTFILES_DIR_NAME}/* ; do
        cp $i ${STAGING_DIR_TARGET}/${BOOTFILES_DIR_NAME}
    done

    # copy Device Tree Blobs of all RPI-models (bootloader will pickup the correct one during the boot-up)

    for i in ${DEPLOY_DIR_IMAGE}/bcm2711-rpi*.dtb ; do
        cp $i ${STAGING_DIR_TARGET}/${BOOTFILES_DIR_NAME}
    done

    # copy Device Tree Overlays

    for i in ${DEPLOY_DIR_IMAGE}/*.dtbo ; do
        cp $i ${STAGING_DIR_TARGET}/${BOOTFILES_DIR_NAME}/overlays
    done

    for i in ${DEPLOY_DIR_IMAGE}/overlay*.dtb ; do
        cp $i ${STAGING_DIR_TARGET}/${BOOTFILES_DIR_NAME}/overlays
    done

    # a bit unsure, if this U-boot's boot-script is needed, because boot-instructions for u-boot
    # are patched into the U-Boot configuration file
    #cp ${DEPLOY_DIR_IMAGE}/boot.scr ${STAGING_DIR_TARGET}/${BOOTFILES_DIR_NAME}

    # Include kernel (kernel8.img) to boot.img: 
    #   seems that mcopy (used by rpi-make-boot-image) cannot copy '.img' -file directly inside boot.img
    #   -> copy without '.img'-extension, and rename back to kernel8.img using mren-tool

    #cp ${DEPLOY_DIR_IMAGE}/u-boot.bin ${STAGING_DIR_TARGET}/${BOOTFILES_DIR_NAME}/${SDIMG_KERNELIMAGE}
    # So first strip .img extension from kernel-file:
    cp ${DEPLOY_DIR_IMAGE}/${KERNEL_IMAGETYPE} \
       ${STAGING_DIR_TARGET}/${BOOTFILES_DIR_NAME}/$(basename -s .img ${SDIMG_KERNELIMAGE})
    
    # FIT-image (which includes Linux kernel with Rasperry PI's base-DTB and overlay-DTBs)
    # TODO: if FIT-image has not been created, kernel and DTBs should be copied step by step...
    #cp ${DEPLOY_DIR_IMAGE}/${KERNEL_IMAGETYPE} ${STAGING_DIR_TARGET}/${BOOTFILES_DIR_NAME}

    # create boot.img using RPI's shell script
    ${S}/tools/rpi-make-boot-image  -b pi4 -d ${STAGING_DIR_TARGET}/${BOOTFILES_DIR_NAME}/ -o ${DEPLOYDIR}/boot.img
    
    # return .img extension to kernel-file
    mren -i "${DEPLOYDIR}/boot.img" $(basename  -s .img ${SDIMG_KERNELIMAGE}) ::${SDIMG_KERNELIMAGE}

    # sign boot.img with private RSA-key
    ${S}/rpi-eeprom/rpi-eeprom-digest \
    -k ${TOPDIR}/conf/private.pem \
    -i ${DEPLOYDIR}/boot.img \
    -o ${DEPLOYDIR}/boot.sig

    # Add stamp in deploy directory
    touch ${DEPLOYDIR}/${PN}-${PV}.stamp
}

# prevent shell-scripts (rpi-make-boot-image, rpi-eeprom-digest) to be compiled!
do_compile[noexec] = "1"

# bootloders, kernel etc. have to exist in DEPLOY-dir, before this recipe can do it's job
do_deploy[depends] += "rpi-config:do_deploy rpi-cmdline:do_deploy \
                       rpi-bootfiles:do_deploy u-boot:do_deploy \
                       virtual/kernel:do_deploy \
                      "

addtask deploy before do_build after do_install

#do_deploy[dirs] += "${DEPLOYDIR}/${BOOTFILES_DIR_NAME}"

#INHIBIT_PACKAGE_STRIP = "1"
#INHIBIT_PACKAGE_DEBUG_SPLIT = "1"

PACKAGE_ARCH = "${MACHINE_ARCH}"
