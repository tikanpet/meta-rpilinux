SUMMARY = "Apply a patch for supporting LUKS2-encryption (for rootfile system) by wic."

LICENSE = "MIT"
LIC_FILES_CHKSUM = "file://${COMMON_LICENSE_DIR}/MIT;md5=0835ade698e0bcf8506ecda2f7b4f302"

SRC_URI = "file://0001-LUKS2-encryption-for-EXTx-partition.patch"

S = "${WORKDIR}"

do_patch() {
  # '/poky/scripts/lib/wic/partition.py' will be patched, so change dir to 'poky'-repository:
  cd ${TOPDIR}/../
  
  git apply --reject --whitespace=fix ${S}/0001-LUKS2-encryption-for-EXTx-partition.patch
  git add scripts/lib/wic/partition.py
  git commit -m "Yocto build-machine: add support for LUKS2 encryption for EXTx-partitions"
}

# well, quite obviously do_patch task is executed before do_image_wic...
addtask patch before do_image_wic

