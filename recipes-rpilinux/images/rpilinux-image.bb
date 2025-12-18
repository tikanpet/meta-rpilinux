require recipes-core/images/core-image-minimal.bb 

IMAGE_INSTALL += "libstdc++ mtd-utils" 
IMAGE_INSTALL += "openssh openssl openssh-sftp-server"

HOSTTOOLS += "mcopy mren mkfs.fat openssl xxd"

IMAGE_BOOT_FILES = "boot.img \
                 boot.sig \
                 "

do_image_wic[depends] += " \
    rpi-bootfiles-secure:do_deploy \
    "

