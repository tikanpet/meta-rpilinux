SUMMARY = "initramfs for cryptsetup"
LICENSE = "MIT"

include recipes-core/images/core-image-initramfs-boot.bb 

INITRAMFS_SCRIPTS = "initramfs-boot"

# VIRTUAL-RUNTIME_base-utils="busybox" by default (see core-image-minimal-initramfs.bb)
#PACKAGE_INSTALL += "cryptsetup" 

