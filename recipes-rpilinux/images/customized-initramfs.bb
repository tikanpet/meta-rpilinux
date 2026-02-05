SUMMARY = "initramfs for booting also encrypted rootfile system with the help of CryptSetup tool"
LICENSE = "MIT"

include recipes-core/images/core-image-initramfs-boot.bb 

INITRAMFS_SCRIPTS = "initramfs-boot-encrypted"

# VIRTUAL-RUNTIME_base-utils="busybox" by default (see core-image-minimal-initramfs.bb)
PACKAGE_INSTALL += "cryptsetup e2fsprogs-resize2fs" 

