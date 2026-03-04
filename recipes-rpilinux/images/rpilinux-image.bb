require recipes-core/images/core-image-minimal.bb 

IMAGE_INSTALL += "libstdc++ mtd-utils" 
IMAGE_INSTALL += "openssh openssl openssh-sftp-server"

HOSTTOOLS += "mcopy mren mkfs.fat openssl xxd"

INITRAMFS_IMAGE = "customized-initramfs"
INITRAMFS_SCRIPTS = "initramfs-boot"

python () {
    if d.getVar('RPI_SECURE_BOOT') == '1':
        # override default setting for IMAGE_BOOT_FILES (defined in Machine-configuration)
        d.setVar('IMAGE_BOOT_FILES', "boot.img boot.sig")
        if d.getVar('RPI_EEPROM_BOOTLOADER_UPDATE') == '1':
            if d.getVar('MACHINE') == 'raspberrypi4-64':
                d.appendVar('IMAGE_BOOT_FILES',' recovery.bin pieeprom.upd pieeprom.sig')
        d.appendVarFlag('do_image_wic', 'depends', ' rpi-bootfiles-secure:do_deploy')
#    else:  bb.parse.SkipRecipe("xx") ?
    if d.getVar('LUKS2_ENCRYPT') == '1':
        d.appendVarFlag('do_image_wic', 'depends', ' wic-encrypt-partition:do_patch')
}

