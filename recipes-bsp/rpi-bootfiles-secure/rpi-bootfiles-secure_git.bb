SUMMARY = "Create RSA-secured boot-image with Raspberry PIx's shell-scripts"
DESCRIPTION = "This repository uses the RPI4/RPI5 USB-boot tools \
for creating signed boot image (boot.img & boot.sig) \
and secure eeprom-bootloader for verifying and loading boot.img. \
Also recovery.bin is deployed for updating eeprom-bootloader automatically \
on the target device."

LICENSE = "GPL-2.0-or-later"
LIC_FILES_CHKSUM = "file://${COMMON_LICENSE_DIR}/GPL-2.0-or-later;md5=fed54355545ffd980b814dab4a3b312c"

inherit deploy nopackages

SRC_URI = " \
    git://github.com/raspberrypi/usbboot.git;protocol=https;branch=master \
"

SRCREV = "101f2d00d959855ca9acdfa9a6ee427f35d1700c"
PV = "master_2026_02_10"

COMPATIBLE_MACHINE = "^rpi$"

S = "${WORKDIR}/git"

DEPENDS = "rpi-bootfiles"

RDEPENDS:${pkgvarcheck} += " \
    e2fsprogs-mke2fs \
"

# rpi-eeprom-config is a tool for creating secured eeprom-configuration.
# rpi-eeprom-digest is a tool for signing boot.img file and secure eeprom-bootloader.
# update-pieeprom.sh is used as frontend-script.
# Those tools are located in submodule (submodule rpi_eeprom.git for usbboot.git),
# so lets fetch also the submodule.
do_configure() {
  cd ${S}
  git submodule update --init --recursive
}

rpi_gather_bootfiles_for_bootimg() {
    # Gather boot-files (start4.elf, fixup4.dat, kernel, device trees etc.) from DEPLOY-folders to STAGING-dir.
    # These files will be stored later into 'boot.img'.

    # Copy RPI-specific bootfiles (start4.elf, fixup4.dat, config.txt, cmdline.txt etc)
    for i in ${DEPLOY_DIR_IMAGE}/${BOOTFILES_DIR_NAME}/* ; do
        cp $i ${STAGING_DIR_TARGET}/${BOOTFILES_DIR_NAME}
    done

    # Include kernel (kernel8.img for RPI4), Device Tree Blobs, overlays and optionally InitRamFs to boot.img.
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

        if ${@bb.utils.contains('MACHINE', 'raspberrypi4-64', 'true', 'false', d)}; then
            # Copy Device Tree Blobs of all RPI4-models (bootloader will pickup the correct one during the boot-up)
            for i in ${DEPLOY_DIR_IMAGE}/bcm2711-rpi*.dtb ; do
                if [ ! -L "$i" ]; then
                    cp $i ${STAGING_DIR_TARGET}/${BOOTFILES_DIR_NAME}
                fi
            done
        fi
        if ${@bb.utils.contains('MACHINE', 'raspberrypi5', 'true', 'false', d)}; then
            # Copy Device Tree Blobs of all RPI4-models (bootloader will pickup the correct one during the boot-up)
            for i in ${DEPLOY_DIR_IMAGE}/bcm2712-rpi*.dtb ; do
                if [ ! -L "$i" ]; then
                    cp $i ${STAGING_DIR_TARGET}/${BOOTFILES_DIR_NAME}
                fi
            done
        fi

        # Copy Device Tree Overlays
        for i in ${DEPLOY_DIR_IMAGE}/*.dtbo ; do
            if [ ! -L "$i" ]; then
                cp $i ${STAGING_DIR_TARGET}/${BOOTFILES_DIR_NAME}/overlays
            fi
        done
        for i in ${DEPLOY_DIR_IMAGE}/overlay*.dtb ; do
            if [ ! -L "$i" ]; then
                cp $i ${STAGING_DIR_TARGET}/${BOOTFILES_DIR_NAME}/overlays
            fi
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
}

do_install() {
    install -d ${DEPLOYDIR}/${BOOTFILES_DIR_NAME}
    install -d ${STAGING_DIR_TARGET}/${BOOTFILES_DIR_NAME}
    install -d ${STAGING_DIR_TARGET}/${BOOTFILES_DIR_NAME}/overlays
    install -d ${STAGING_DIR_TARGET}/eeprom_bootloader

}

rpi4_secure_eeprom_bootloader() {
    # -------------------------------------------------------------------------------
    #               Secure eeprom-bootloader for Raspberry PI4

    # update-pieeprom.sh executes next steps:
    # 
    # EEPROM's default boot-configuration 'secure-boot-recovery/boot.conf' is used.
    # Configuration contains flag 'SIGNED_BOOT=1', but no OTP-settings for locking
    # eeprom-bootloader forever to secured one. So it's still possible to roll back to non-secure world!
    # User's public RSA-key is included into boot-config, so that bootloader can 'open' signed boot.img
    # (rpi-eeprom-config can extract public key from private.pem file...).
    # boot-config is signed with user's private RSA-key (RSA2048).
    # Secured boot-config is included into bootloader (pieeprom.bin). 
    # signature-file 'pieeprom.sec' (SHA256 checksum) is created for eeprom-bootloader.

    cd ${S}/secure-boot-recovery/

    ../tools/update-pieeprom.sh -k ${TOPDIR}/conf/private_sb.pem \
                                -o ${STAGING_DIR_TARGET}/eeprom_bootloader/pieeprom_secure.bin

    # Deploy recovery.bin, secured eeprom-bootloader and signature:

    # recovery.bin on SD-card's boot-partition triggers the automatic update-process
    # of eeprom-bootloader by first-stage ROM-bootloader
    # (bootcode4.bin is symbolic link to ../rpi-eeprom/firmware-2711/latest/recovery.bin)
    cp bootcode4.bin ${DEPLOYDIR}/recovery.bin

    # Because eeprom-bootloader below is named as 'pieeprom.upd', 'recovery.bin' will be renamed
    # automatically to 'RECOVERY.000' after succeeded flashing of the new eeprom-bootloader.
    # This avoids the bootloader to be flashed again and again after next boot-ups!

    # If eeprom_bootloader was named to 'pieeprom.bin', RaspberryPI's leds & HDMI-output
    # would indicate the progress of the flashing (green/red). In this case remember to rename
    # (or remove) manually 'recovery.bin' on SD-card after flashing.

    cp ${STAGING_DIR_TARGET}/eeprom_bootloader/pieeprom_secure.bin ${DEPLOYDIR}/pieeprom.upd

    cp ${STAGING_DIR_TARGET}/eeprom_bootloader/pieeprom_secure.sig ${DEPLOYDIR}/pieeprom.sig
}

rpi4_non_secure_eeprom_bootloader() {
    # -------------------------------------------------------------------------------
    #               Non-Secure eeprom-bootloader for Raspberry PI4

    cd ${S}/recovery/

    ./update-pieeprom.sh -o ${STAGING_DIR_TARGET}/eeprom_bootloader/pieeprom_non_secure.bin

    # Deploy recovery.bin, eeprom-bootloader and signature:

    # bootcode4.bin is symbolic link to ../rpi-eeprom/firmware-2711/latest/recovery.bin
    cp bootcode4.bin ${DEPLOYDIR}/recovery.bin

    cp ${STAGING_DIR_TARGET}/eeprom_bootloader/pieeprom_non_secure.bin ${DEPLOYDIR}/pieeprom.upd
    cp ${STAGING_DIR_TARGET}/eeprom_bootloader/pieeprom_non_secure.sig ${DEPLOYDIR}/pieeprom.sig
}

do_deploy() {
    # Store boot-files from DEPLOY-dir to STAGING-dir 
    rpi_gather_bootfiles_for_bootimg

    # Store boot-files from STAGING-dir to boot.img.
    # Sign boot.img by private RSA-key by creating boot.sig.
    # Finally deploy boot.img and boot.sig.
    # Optionally create secured EEPROM-bootloader, which is signed with same private RSA-key than boot.img.
    # EEPROM-bootloader will be stored alongside boot.img and boot.sig.

    if ${@bb.utils.contains('RPI_EEPROM_BOOTLOADER_UPDATE', '1', 'true', 'false', d)}; then
        # update eeprom-bootloader
        if ${@bb.utils.contains('MACHINE', 'raspberrypi4-64', 'true', 'false', d)}; then
            if ${@bb.utils.contains('RPI_SECURE_BOOT', '1', 'true', 'false', d)}; then
                # create secure eeprom-bootloader
                rpi4_secure_eeprom_bootloader
            fi
        #else RPI5 not supported: boot.img could be verified by setting 'boot_ramdisk=1' in 'config.txt'
        fi
    fi

    # create boot.img using RPI's shell script
    # TODO: create boot.img for Raspberry PI5...
    ${S}/tools/rpi-make-boot-image \
        -b pi4 \
        -d ${STAGING_DIR_TARGET}/${BOOTFILES_DIR_NAME} \
        -o ${DEPLOYDIR}/boot.img
    
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

# bootloaders, kernel etc. have to exist in DEPLOY-dir, before this recipe can do it's job
do_deploy[depends] += "rpi-config:do_deploy rpi-cmdline:do_deploy \
                       rpi-bootfiles:do_deploy u-boot:do_deploy \
                       virtual/kernel:do_deploy \
                      "

addtask deploy before do_build after do_install

#do_deploy[dirs] += "${DEPLOYDIR}/${BOOTFILES_DIR_NAME}"

#INHIBIT_PACKAGE_STRIP = "1"
#INHIBIT_PACKAGE_DEBUG_SPLIT = "1"

PACKAGE_ARCH = "${MACHINE_ARCH}"
