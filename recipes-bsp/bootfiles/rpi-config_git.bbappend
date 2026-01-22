DESCRIPTION = "Additions for config.txt file for the Raspberry Pi."

do_deploy:append() {
    # Added instruction for loading InitRamsFS to RAM. But, if InitRamsFS is bundled into Linux kernel or fitImage,
    # kernel or U-boot (not start.elf) will load InitRamsFS. 
    if [ -n "${INITRAMFS_IMAGE}" -a "${KERNEL_IMAGETYPE}" != "fitImage" -a -z "${INITRAMFS_IMAGE_BUNDLE}" ]; then
        sed -i \
        '/#initramfs initramf.gz 0x00800000/ c\initramfs ${INITRAMFS_IMAGE}.${INITRAMFS_FSTYPES} followkernel' \
        $CONFIG
    fi
}

