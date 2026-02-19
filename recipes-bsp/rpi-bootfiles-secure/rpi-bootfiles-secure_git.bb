SUMMARY = "Create RSA-secured boot-image with Raspberry PIx's shell-scripts"
DESCRIPTION = "This repository uses the RPI4/RPI5 USB-boot tools \
for creating signed boot image (boot.img & boot.sig) \
and secure eeprom-bootloader for verifying and loading boot.img. \
Also recovery.bin is deployed for updating eeprom-bootloader automatically \
on the target device."
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

rpi_secure_eeprom_bootloader() {
    # -------------------------------------------------------------------------------
    #               Secure eeprom-bootloader

    # Sign boot-config with private RSA-key 
    # This config (/secure-boot-recovery/boot.conf) contains flag 'SIGNED_BOOT=1',
    # but no OTP-settings, so it's still possible to roll back to non-secure world!

    ${S}/rpi-eeprom/rpi-eeprom-digest \
    -k ${TOPDIR}/conf/private_sb.pem \
    -i ${S}/secure-boot-recovery/boot.conf \
    -o ${STAGING_DIR_TARGET}/eeprom_bootloader/boot.conf.sig
    # Create secure eeprom-bootloader

    ${S}/rpi-eeprom/rpi-eeprom-config \
    -p ${TOPDIR}/conf/public_sb.pub \
    -c ${S}/secure-boot-recovery/boot.conf \
    -d ${STAGING_DIR_TARGET}/eeprom_bootloader/boot.conf.sig \
    -o ${STAGING_DIR_TARGET}/eeprom_bootloader/pieeprom_secure.bin \
       ${S}/rpi-eeprom/firmware-2711/latest/pieeprom-2025-08-27.bin

    #  Signature-file (SHA256 checksum) for eeprom-bootloader: 
    ${S}/rpi-eeprom/rpi-eeprom-digest \
    -i ${STAGING_DIR_TARGET}/eeprom_bootloader/pieeprom_secure.bin \
    -o ${STAGING_DIR_TARGET}/eeprom_bootloader/pieeprom_secure.sig

    # Deploy recovery.bin, secured eeprom-bootloader and signature:

    # recovery.bin on SD-card's boot-partition triggers the automatic update-process
    # of eeprom-bootloader by first-stage ROM-bootloader.
    cp ${S}/rpi-eeprom/firmware-2711/latest/recovery.bin ${DEPLOYDIR}

    # TODO: add support for RPI5 (2712-firmware)...

    # Because eeprom-bootloader below is named as 'pieeprom.upd', 'recovery.bin' will be renamed
    # automatically to 'RECOVERY.000' after succeeded flashing of the new eeprom-bootloader.
    # This avoids the bootloader to be flashed again and again after next boot-ups!

    # If eeprom_bootloader was named to 'pieeprom.bin', RaspberryPI's leds (green/red)
    # would indicate the progress of the flashing. In this case remember to rename (or remove)
    # manually 'recovery.bin' on SD-card after flashing.

    cp ${STAGING_DIR_TARGET}/eeprom_bootloader/pieeprom_secure.bin ${DEPLOYDIR}/pieeprom.upd

    cp ${STAGING_DIR_TARGET}/eeprom_bootloader/pieeprom_secure.sig ${DEPLOYDIR}/pieeprom.sig
}

do_deploy() {
    install -d ${DEPLOYDIR}/${BOOTFILES_DIR_NAME}
    install -d ${STAGING_DIR_TARGET}/${BOOTFILES_DIR_NAME}
    install -d ${STAGING_DIR_TARGET}/${BOOTFILES_DIR_NAME}/overlays
    install -d ${STAGING_DIR_TARGET}/eeprom_bootloader

    # Gather boot-files (start.elf, fixup.dat, kernel, device trees etc.) from DEPLOY-folders 
    # to STAGING-dir. Store those files to boot.img.
    # Sign boot.img by private RSA-key by creating boot.sig.
    # Finally deploy boot.img and boot.sig.
    # Optionally create secured EEPROM-bootloader, which is signed with same private RSA-key than boot.img.
    # EEPROM-bootloader will be stored alongside boot.img and boot.sig.

    # create secure eeprom-bootloader (if update-flag set)
    if ${@bb.utils.contains('RPI_EEPROM_BOOTLOADER_UPDATE', '1', 'true', 'false', d)}; then
        rpi_secure_eeprom_bootloader
    fi

    # Copy RPI-specific bootfiles (start.elf, fixup.dat etc)
    for i in ${DEPLOY_DIR_IMAGE}/${BOOTFILES_DIR_NAME}/* ; do
        cp $i ${STAGING_DIR_TARGET}/${BOOTFILES_DIR_NAME}
    done

    # Include kernel (kernel8.img for RPI4/5), Device Tree Blobs, overlays and optionally InitRamFs to boot.img.
    # If U-boot and FIT-image is enabled, include U-boot and FIT-image
    # (FIT-image contains kernel, DTBs, DTB-overlays, InitRamFS).

    # Seems that mcopy (used by rpi-make-boot-image) cannot copy '.img' -file directly inside boot.img
    #   -> copy without '.img'-extension, and rename back to kernel8.img using mren-tool
    # Notice that if U-Boot has been enabled, 'start.elf' naturally runs U-Boot instead of kernel.
    # So also U-Boot will be renamed first to kernel8, and then to kernel8.img.  

    if ${@bb.utils.contains('RPI_USE_U_BOOT', '1', 'true', 'false', d)}; then
        # copy U-Boot (strip '.img'-extension from kernel-file):
        cp ${DEPLOY_DIR_IMAGE}/u-boot.bin \
           ${STAGING_DIR_TARGET}/${BOOTFILES_DIR_NAME}/$(basename -s .img ${SDIMG_KERNELIMAGE})

        # U-Boot's boot-script, possible not even needed, because boot-instructions for U-Boot
        # are patched into the U-Boot's configuration file (recipes-bsb/u-boot: rpi_arm64_defconfig)
        cp ${DEPLOY_DIR_IMAGE}/boot.scr ${STAGING_DIR_TARGET}/${BOOTFILES_DIR_NAME}

        if ${@bb.utils.contains('KERNEL_IMAGETYPE_UBOOT', 'fitImage', 'true', 'false', d)}; then
            # Copy FIT-image, which contains all of these: 
            # Linux kernel, Raspberry PI's base-DTB, overlay-DTBs, optionally InitRamFS

            # Optionally InitRamFs
            if ${@bb.utils.contains('INITRAMFS_IMAGE', 'customized-initramfs', 'true', 'false', d)}; then
                # copy FIT-image included with initramfs
                # (symbolic link to fitImage is e.g. 'fitImage-customized-initramfs-raspberrypi4-64-raspberrypi4-64')
                cp ${DEPLOY_DIR_IMAGE}/${KERNEL_IMAGETYPE}-${INITRAMFS_IMAGE}-${MACHINE}-${MACHINE} \
                   ${STAGING_DIR_TARGET}/${BOOTFILES_DIR_NAME}/${KERNEL_IMAGETYPE}
            else
                cp ${DEPLOY_DIR_IMAGE}/${KERNEL_IMAGETYPE} ${STAGING_DIR_TARGET}/${BOOTFILES_DIR_NAME}                
            fi
        # else
        #   U-Boot loads kernel directly (not via FIT-image), so kernel should be copied here...
        #   But: this configuration is not supported, so U-boot and FIT-image MUST both be enabled... 
        fi
    else
        # No U-Boot

        # Copy Device Tree Blobs of all RPI-models (bootloader will pickup the correct one during the boot-up)
        for i in ${DEPLOY_DIR_IMAGE}/bcm2711-rpi*.dtb ; do
            cp $i ${STAGING_DIR_TARGET}/${BOOTFILES_DIR_NAME}
        done

        # Copy Device Tree Overlays
        for i in ${DEPLOY_DIR_IMAGE}/*.dtbo ; do
            cp $i ${STAGING_DIR_TARGET}/${BOOTFILES_DIR_NAME}/overlays
        done
        for i in ${DEPLOY_DIR_IMAGE}/overlay*.dtb ; do
            cp $i ${STAGING_DIR_TARGET}/${BOOTFILES_DIR_NAME}/overlays
        done

        # Copy Linux Kernel (strip '.img'-extension from kernel-file):
        cp ${DEPLOY_DIR_IMAGE}/${KERNEL_IMAGETYPE} \
           ${STAGING_DIR_TARGET}/${BOOTFILES_DIR_NAME}/$(basename -s .img ${SDIMG_KERNELIMAGE})

        # Optionally InitRamFs
        # If InitRamsFS is bundled into Linux kernel, it will not be included separately into boot.img.
        if [ -n "${INITRAMFS_IMAGE}" -a -z "${INITRAMFS_IMAGE_BUNDLE}" ]; then
            # Copy initramfs (e.g. 'customized-initramfs-raspberrypi4-64.cpio.gz').
            # 'start.elf' needs to know this name, so it must be defined also in 'config.txt'...
            cp ${DEPLOY_DIR_IMAGE}/${INITRAMFS_IMAGE}-${MACHINE}.${INITRAMFS_FSTYPES} \
               ${STAGING_DIR_TARGET}/${BOOTFILES_DIR_NAME}/${INITRAMFS_IMAGE}.${INITRAMFS_FSTYPES}
        fi
    fi

    # create boot.img using RPI's shell script
    ${S}/tools/rpi-make-boot-image  -b pi4 -d ${STAGING_DIR_TARGET}/${BOOTFILES_DIR_NAME}/ -o ${DEPLOYDIR}/boot.img
    
    # return '.img' extension to kernel-file
    mren -i "${DEPLOYDIR}/boot.img" $(basename  -s .img ${SDIMG_KERNELIMAGE}) ::${SDIMG_KERNELIMAGE}

    # create 'boot.sig' by signing 'boot.img' with private RSA-key
    ${S}/rpi-eeprom/rpi-eeprom-digest \
    -k ${TOPDIR}/conf/private_sb.pem \
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
