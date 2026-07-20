# meta-rpilinux
Additional Yocto metalayer for Raspberry PI for Secure Boot and Secure root file system (LUKS2 encryption).
Older versions than Raspberry 4 don't support secure boot.

I have tested these new recipes only in Raspberry PI4B, because I don't own other Raspberry devices.

Also recipe for setting up U-boot (with FIT-image) is included. Unfortunately Secure Boot doesn't work yet,
if U-boot is included into the secure boot-image...

My focus here is mostly issues related in the securing process. So this meta-layer has instructions for creating just a small root-file system (see rpilinux-image.bb), which herits just 'core-image-minimal.bb'. 

## Introduction to Secure Boot

Secure Boot is a mechanism to secure components responsible for booting the device up (bootloaders, kernel, device-tree,
optionally device-tree overlays and initramfs, configuration files) by the help of asynchronic (private/public) cryptographic keys.
Bootfiles are encrypted with private key and decrypted with public key. Avoid to publish your private key, so never store it at least into target device (like Raspberry PI). Instead public key is stored into target device. If public key is locked by storing ('fusing') it e.g. into **'One Time Programmable' memory (OTP)**, so without possibility to change it anymore, private key cannot be changed either. That's because private key is unique for single public key. So **secured bootfiles can not be altered** anymore without knowing your private key. If a hacker creates a new key-pair, changes some bootfile(s) and encrypts the bootfile(s) with his own private key, bootfiles are still decrypted using public key originally stored into target device (without the chance to change it). Decryption will fail, and device will not boot up...

In the future, efficient quantum-computer can possible derive by calculating the private key from public key (at least for ŔSA-keys)...

Notice that even secured bootfiles (e.g configuration) **can be read** normally. For that reason, if e.g. root-filesystem is encrypted too, the decryption key (which is actually same as encryption key) for decrypting the filesystem cannot be stored even inside the secured boot-partition.

Raspberry PI's solution for Secure Boot is to create single image called **'boot.img'** including all executables
required to get RPI booted up, so in the case of RPI4/CM4 at least:
 'start4.elf', 'config.txt', 'cmdline.txt', 'bcm2711-rpi-4.dtb', 'fixup4.dat', 'kernel', DTB overlays, optionally 'initramfs'.

See [Raspberry PI4 Boot Security](https://pip-assets.raspberrypi.com/categories/1260-security/documents/RP-004651-WP-2-Raspberry%20Pi%204%20Boot%20Security.pdf?disposition=inline) for details.


'boot.img' is secured by calculating an unique signature (hexadecimal sha256 checksum of the file content), and encrypting the signature with customer's private RSA-key. Secured signature is stored to **'boot.sig'** file. EEPROM-bootloader running in RPI- target device calculates the signature of the 'boot.img', opens the corresponding signature from 'boot.sig' with customer's **public RSA-key** (stored to Raspberry Pi's **EEPROM**), and compares those signatures. If signatures match, booting process will continue. 

Eeprom-bootloader containing secure boot-configuration has to be updated to Raspberry PI. Customer's public RSA-key is stored to EEPROM-configuration, when secured EEPROM-bootloader is written to EEPROM. Also EEPROM-bootloader is signed, by the help of ROM-bootloader (first stage bootloader). ROM-bootloader can then start the eeprom-bootloader securely at the first place, by verifying signature with public RSA-key stored in ROM (and OTP?). Secure eeprom-bootloader can then verify and load the contents from boot.img.

Signed security is only available on the B1/C0 stepping (and later versions) of the BCM2711 SOC. The B0 stepping does not have the RSA public keys in its ROM that would be needed for the ROM to verify the EEPROM-bootloader. So with B0-stepping it's always possible to switch back to nonsecure eeprom-bootloader. With newer SOCs, secure eeprom-bootloader and public RSA-key can be locked, to avoid to flash a non-secure eeprom bootloader anymore, or change the public key. I myself own only B0 stepping-board, so I cannot lock eeprom bootloader.

With 'meta-rpilinux' meta-layer, you can do first steps towards the final secure-boot (creating secured eeprom-bootloader, boot.img and boot.sig), and get the good feeling about, how it works. If you hesitate, you can still easily flash the original non-secure eeprom bootloader, and continue without using 'boot.img' and 'boot.sig'. **So this meta-layer doesn't have the rules for locking the eeprom-bootloader and public key (to OTP).** You need to use e.g. **rpiboot**-tool for locking the device via USB.

eeprom-bootloader within public RSA-key can be **automatically** updated to RPI's EEPROM in the case of **RPI4/400**-models (not with CM4/CM4S/CM5/CM5S). 'meta-rpilinux' has support for automatical update by storing the bootloader with **recovery.bin** in the boot-partition of SD-card. So update will start automatically, when RPI4/400 boots up.

Although RPI5-eeprom bootloader could also be updated automatically, this metalayer has **no support** for creating a secured eeprom-bootloader for **Raspberry PI5**. Unlike BCM2711 (RPI4/CM4), BCM2712 SOC doesn't have support to test a secured eeprom-bootloader without locking it. But it's possible to check the signed boot.img by modifying 'config.txt':
**boot_ramdisk = 1** ([RPI config.txt](https://www.raspberrypi.com/documentation/computers/config_txt.html)).


**'rpi-bootfiles-secure.bb'**-recipe contains all rules for fetching and executing the helper scripts from [USB-boot tools](https://github.com/raspberrypi/usbboot/tree/master), like rpi-eeprom-config, rpi-eeprom-digest and rpi-make-boot-image. **rpiboot**-tool is also found there for updating eeprom-bootloader and locking the device for secure-boot via **USB**.

Notice that because **SIGNED_BOOT**-flag is set to 1 in '/secure-boot-recovery/boot.conf', eeprom-bootloader starts to accept only signed boot.img files. 

If **program_pubkey** was set to 1 in 'boot.conf', secure-boot would be locked for good. After that there would be no 'turn back to non-secure boot'-option anymore (>= B1/C0 stepping versions). Check also Raspberry PI's manuals (e.g [USB-boot tools](https://github.com/raspberrypi/usbboot/tree/master): recovery/secure-boot-recovery sections) for the last steps: enabling **rpiboot**, fusing of customer public RSA-key in OTP etc. 

## LUKS2-Encryption/decryption of the root-file system using Cryptsetup
Raspberry PI 4 (and 5) stores also the filesystem into SD-card. Because SD-card is easily removable from the device, a hacker can scan your files easily. So it could be good idea to secure also partition(s) containing e.g. root-filesystem.

Cryptsetup-tool is used here for encrypting/decrypting a single partition of the file-system. Cryptsetup is a frontend (command-line utility) for actual crypto-functionality executed in the Linux kernel-side (**dm-crypt**). Cryptsetup employs also LUKS (Linux Unified Key Setup) for metadata management, like for storing cryptographic keys. LUKS2-version (default) is used. LUKS2 reserves 16 MB header-section for metadata, which is written into the beginning of the partition. Metadata could be excluded into the separate header-file as well, but this meta-layer doesn't support the header-stripping. 

Unlike Secure boot, LUKS uses synchronous (symmetric) key algorithm. So same cryptographic key will be used for encrypting and decrypting the partition containing the the file system. Default chiphering mode is **aes-xts-plain64**, and **master key** (for actual encryption/decryption) is 512 bit lenght. Master key is created automagically when starting the encryption.

Encrypted file-system is a LUKS2-container, which will be opened by the **user-specified key (interactive passphrase or key-file)**.
From 'Cryptsetup --help':

 *Default compiled-in key and passphrase parameters:*
 *Maximum keyfile size: 8192kB, Maximum interactive passphrase length 512 (characters)*

Master-key is secured from the hacker, but remember to keep all user-keys in secret place.

Up to eight user-keys can be stored into LUKS2-header, but two keys are created: 'random number' key-file and 'passphrase' key. Both can naturally be used for encryption/decryption of the filesystem, but only random key is used in the encryption phase, and passphrase (by default) in the decryption phase.

In this meta-layer, encryption is done already in PC-side during the building of a WIC-image (to take advantage of PC's performance). Only **EXTx-partitions** (x=2,3,4) are encrypted so far.

Decryption is naturally done in the target-device. Because Linux kernel cannot execute any user-space tools (like Cryptsetup), I created a simple InitRamfs ('early user space'), where Cryptsetup is executed, and filesystem is then decrypted. After executing InitRamfs, kernel can load actual decrypted filesystem.

Notice that, unlike Raspberry PI5, Raspberry PI4 doesn't have HW-acceleration support for AES-chiphering.
Adiantum is a high-performance encryption alternative, providing much faster disk encryption, but AES-ADIANTUM is not supported yet here.

### More about Encryption

A partition containing rootfile-system is encrypted already during 'bitbaking' the image. In this way the encryption procedure is more secure, than e.g. executing the encryption in the target device. Yes, there could have been option for e.g. storing a non-encrypted filesystem into the target, and encrypting it to some other partition of SD-card during the boot-up procedure. But then you have non-encrypted file system visible atleast for a while in your SD-card...

Yocto doesn't allow any user-action, so Cryptsetup is disallowed to ask any confirmation. Otherwise the build will fail.
Therefore, a 'random-value' keyfile is used instead of asking a passphrase from the user, and batch-mode is enabled.

Yocto doesn't allow any root-accesses as well. So to avoid 'sudoing', Yocto's procedure for creating WIC-image hasn't access any '/dev'-interface (like /dev/loop/) when formatting the EXTx-partition (and storing the file system there). Instead a specific file (a sparse type) was created as EXTx-formatted. But now when adding encryption-logic, Cryptsetup accesses other root-directory: '/run' by default...

To avoid usage of /run-folder, **'--disable-locks'**-option is used for allowing Cryptsetup to be executed without 'sudo'.

Encryption is done after, the rootfs-data is written to the partition (using **'--reencrypt'** option). That's because of avoiding usage of '/dev/mapper/...' device (otherwise sudoing again needed), if encryption was done by 'luksFormat',..., 'open' etc.
See details in comments from patch-file appended by **'wic-encrypt-partition.bb'**. So rootfs-data has to be shifted for getting some room for LUKS2-header. Also one resizing of the partition (with resize2fs-tool) is needed after successfull encryption, but this is done in the decryption-phase (in the Raspberry PI-target). 


### More about Decryption

Cryptsetup and resize2fs need to be installed into Initramfs. Default mode is to ask passphrase from the user (your 'passphrase_luks.key'). Batch-mode therefore has to be disabled for Cryptsetup.

Passphrase is used, because no key-file cannot be stored into secured, but non-encrypted (boot) partition (inside initramfs),
Otherwise the key(s) are relatively easy to steal by the hacker. So the user is advised to type the passphrase. Optionally keyfile could be used, if it's detected e.g. from USB-stick (not supported yet).

Keyfile could be stored into Raspberry PI's OTP memory as well. USB-boot tools contains 'rpi-otp-private-key' script
for storing and reading the key. But it's not supported in this meta-layer, and I'm a bit sceptical,
if OTP-register is a save place for this usage. It could be possible to read from there by the hacker.
If anyone has played with TPM-security chip connected to Raspberry, it's a good place for storing the key too...

resize2fs has to be executed (once), to get EXTx-partition (x=2,3,4) to be adjusted after data-shifting,
which was needed for inserting LUKS2-header in the beginning of the partition in the encryption-phase.

## Setup for Yocto-environment with Raspberry BSP and meta_rpilinux

```
mkdir Yocto
cd Yocto
git clone -b scarthgap git://git.yoctoproject.org/git/poky.git
git clone -b scarthgap git://git.openembedded.org/meta-openembedded.git
git clone -b scarthgap git://git.yoctoproject.org/meta-raspberrypi.git
git clone git@github.com:tikanpet/meta-rpilinux.git
cd poky
```

'meta-raspberrypi'-layer contains Board Support Package (BSP) for getting Raspberry PI up and running.
'meta-openembedded'-layer's 'meta-oe' directory contains recipes for building Cryptsetup-tool
for decrypting the root file-system in RaspberryPI.

Install these packages (e.g. in Ubuntu with apt-get install, pycryptodome alternatively with 'pip install pycryptodome')
```
mtools, dosfstools, python3-pycryptodome, openssl, xxd, cryptsetup
```

dosfstools contains 'mkfs.fat' for creating 'boot.img' (RPI's secure boot-image).
mtools contains e.g. 'mcopy' and 'mren' for pushing RPI-bootfiles to 'boot.img'.
python3-pycryptodome is used for encryption, decryption, hashing and signature verification for secure eeprom-bootloader and 'boot.img'.
Cryptsetup is needed for encrypting a single partition of the file-system.
openssl is used for creating asynchronous private/public key-pair (for secure boot),
and synchronous key for partition encryption.

Setup environment for creating build-environment: 

```
 source oe-init-build-env
```

Set these RaspberryPI-related metalayers in 'poky/build/bblayers.conf':

```
 meta-raspberrypi
 meta-rpilinux
 meta-openembedded/meta-oe
```

Create RSA-keys (**RSA2048**) for secure boot:

```
   openssl genrsa 2048 > private_sb.pem
   // public-key is embedded into .pem-file, but rpi-eeprom-config can extract it.
   // So no need to extract it here (openssl rsa -in private_sb.pem -pubout -out public_sb.pub)  

   Copy 'private_sb.pem' to '...poky/build/conf'-directory
```

Create cryptographic random-key and passphrase-key for LUKS2-encryption:

```
Random key (e.g. 4 KB):
   dd if=/dev/random of=luks.key bs=512 count=8
   # or openssl genrsa 4096 > luks.key

Passphrase (e.g. up to 32 characters):
   dd if=/dev/random bs=32 count=1 of=passphrase_luks.key
   printf "Type here some passphrase for starting the decryption..." > passphrase_luks.key
   # some special characters (e.g. ',.;) are recommended (to improve security)

Copy 'luks.key' and 'passphrase_luks.key' to '...poky/build/conf'-directory
```
Random-key file and passphrase-key are added during the encryption into LUKS2-header.

You could protect all keys by e.g. setting root-user rights for the keys, except when building an Yocto-image:
```
read-access only for the user:
    chmod 400 'your key'
after the build:
    sudo chown root:root 'your key'
before starting the build:
    sudo chown $(whoami):$(whoami) 'your key'
```
Set correct machine e.g. in your 'poky/build/local.conf', e.g. for Raspberry PI4:

```
 MACHINE ?= "raspberrypi4-64"
```

Set also variables related to secured boot and secured filesystem in local.conf:

```
HOSTTOOLS += "mcopy mkfs.fat openssl xxd mren cryptsetup"

RPI_SECURE_BOOT = "1"
RPI_EEPROM_BOOTLOADER_UPDATE = "1"

INITRAMFS_IMAGE = "customized-initramfs"
#INITRAMFS_SCRIPTS = "initramfs-boot-encrypted"

WICVARS:append = " LUKS2_ENCRYPT"
LUKS2_ENCRYPT = "1"
```
Background for these settings is explaned later below. 

Check that **Linux kernel** running in Raspberry PI **supports InitramFS** (should already be enabled by default),
directly from '.config'-file  or by using e.g. 'menu-config' (**bitbake -c menuconfig virtual/kernel**):

```
General setup  ---> 
    [*] Initial RAM filesystem and RAM disk (initramfs/initrd) support (CONFIG_BLK_DEV_INITRD)
    [*]   Support initial ramdisk/ramfs compressed using gzip (CONFIG_INITRAMFS_COMPRESSION_GZIP=y)
          (other compressions could be enabled as well...)
    ()    Initramfs source file(s) (CONFIG_INITRAMFS_SOURCE == "")
```
Because Initramfs-image is NOT bundled into the kernel, 'Initramfs source file(s)' will not be defined either.

Encryption-related kernel settings should already be enabled as default, but for your interest check that **kernel supports DM-Crypt** (device mapper and crypt target).
```
[*] Enable loadable module support  --->
Device Drivers --->
  [*] Multiple devices driver support (RAID and LVM)  --->
    <*> Device mapper support (CONFIG_BLK_DEV_DM)
    <*> Crypt target support (CONFIG_DM_CRYPT)
```
Check that next cryptographic API functions are enabled:
```
[*] Cryptographic API  --->
  Block ciphers --->
    <*> AES (Advanced Encryption Standard) (CONFIG_CRYPTO_AES)
  Length-preserving ciphers and modes --->
    <*> XTS (XOR Encrypt XOR with ciphertext stealing) (CONFIG_CRYPTO_XTS)
  Hashes, digests, and MACS --->
    <*> SHA-224 and SHA-256 (CONFIG_CRYPTO_SHA256)
  Userspace interface --->
    <*> Hash algorithms (CONFIG_CRYPT_USER_API_HASH)
    <*> Symmetric key cipher algorithms (CONFIG_CRYPTO_USER_API_SKCIPHER)
```
Build the image for Raspberry PI4:
```
 bitbake rpilinux
```

You can list the content of the target-image (untar '.wic.bz2' first).
If LUKS2_ENCRYPT=0:

```
  wic ls tmp/deploy/images/raspberrypi4-64/rpilinux-image-raspberrypi4-64.rootfs-xxxxx.wic
    Num     Start        End          Size      Fstype
     1       4194304    140509183    136314880  fat16
     2     142606336    197132287     54525952  ext4
```
If LUKS2_ENCRYPT=1, partition 2 (ext4) cannot be accessed with list-command.

You can list e.g. FAT16-partition for checking attached bootfiles ('boot.img' etc):

  wic ls tmp/deploy/images/raspberrypi4-64/rpilinux-image-raspberrypi4-64.rootfs-xxxxx.wic:1

Image can be written to SD-card e.g. with bmaptool (on Debian-distributions: **sudo apt install bmap-tools**).
First after inserting SD-card into PC, check if any partitions (like /dev/mmcblk0p1) are automounted, and unmount those.
Then flash the image:

```
sudo bmaptool -d copy tmp/deploy/images/raspberrypi4-64/rpilinux-image-raspberrypi4-64.rootfs-xxxxx.wic.bz2 /dev/mmcblk0
```
Connect your USB-keyboard and monitor debug-messaging from e.g. HDMI-display after powering your device on.
**'secure-boot'** and **Loading boot.img** messages should be prompted, when secondary bootloader is started.
When kernel starts, it executes Initramfs, where user is advised to **type Passphrase** (for LUKS-decryption).

## Details for configuration settings:

**HOSTTOOLS**-variable publishes the tools (installed in your native Linux-distro) also in Yocto-environment.
Notice that 'boot.img' can be created even without using 'mcopy', but '/dev/loop'-device is needed,
ending up that Yocto-environment isn't sudoless anymore... See USB-boot tools: 'rpi-make-boot-image.sh'

**RPI_SECURE_BOOT**-flag enables the creation of boot.img and signature file.

**RPI_EEPROM_BOOTLOADER_UPDATE**-flag enables the creation of Secure eeprom-bootloader (for Raspberry PI4).
If the update of the bootloader into the Raspberry's EEPROM will be successfull, 
unset the flag before the next builds.

'pieeprom.upd', 'pieeprom.sig' and 'recovery.bin' are created for eeprom-bootloader, and stored during the building
alongside 'boot.img' and 'boot.sig' into the boot-partition.
If ROM-bootloader (after switching RPI's power on) detects 'recovery.bin' from the **SD-card**, updating of eeprom-bootloader to a newer version (a secured one) starts automatically instead of the bootup process. Compute Modules CM4/CM4S don't support automatic updates (ROM-bootloader cannot load recovery.bin from **eMMC**). Instead CM5 and newer versions might support of loading of recovery.bin from eMMC, but the support is not added to meta-rpilinux. See [Update the bootloader](https://www.raspberrypi.com/documentation/computers/raspberry-pi.html)/'Update the bootloader' for more details.

Unfortunately there are no good indicators for showing the progress of the update (when bootloader is named as 'pieeprom.upd'), unless you have a setup serial connection for debug-messaging. If the update succeeded, 'recovery.bin' would be automatically renamed to 'RECOVERY.000' for avoiding 
the eternal loop of the updates...

You can also rename 'pieeprom.upd' to 'pieeprom.bin' (from rpi-bootfiles-secure.bb/rpi_secure_eeprom_bootloader and rpilinux-image.bb) for getting green led flashing rapidly during the flashing (red led to indicate the failure).
But rename (or totally remove) 'recovery.bin' from SD-card manually after successfull flashing...

**LUKS2_ENCRYPT**-flag enables the encryption of EXTx-partition (containing the root filesystem). This flag has to be appended
to WIC-variables (**WICVARS**) for accessing it in 'poky/script/lib/wic/partition.py'. I made also a receipe ('wic-encrypt-partition.bb') for patching the encryption-routine into 'partition.py'.

Because Cryptsetup-tool is used for the decryption, InitRamFS (early user space) is needed.
When setting **INITRAMFS_IMAGE** to **customized-initramfs**, InitRamFS including decryption feature is added to the image.
That's because 'customized-initramfs.bb' sets **INITRAMFS_SCRIPTS**-flag to **initramfs-boot-encrypted**,
causing 'initramfs-boot-encrypted.bb' to call a script 'init-boot-encrypted.sh', which calls Cryptsetup-tool.
Initramfs-image is NOT bundled into the kernel (**INITRAMFS_IMAGE_BUNDLE == ""**).
Default for **INITRAMFS_FSTYPES** is **"cpio.gz"**.
So gzipped file **customized-initramfs.cpio.gz** will be created.

Next rule to 'config.txt' is added for indicating that Initramfs has to be loaded to RAM by 'start.elf' (see 'rpi-config_git.bbappend'):

   **initramfs ${INITRAMFS_IMAGE}.${INITRAMFS_FSTYPES} followkernel**

## TODO:
- LUKS-decryption: detect the luks.key from USB-stick...
- Check if it is easy to add aes-adiantum (instead of aes-xts-plain64) for Raspberry PI4
- In some point I could offer the LUKS-encryption-function also to Poky's upstream.
  But the encryption-routine could be expanded first to support other partition-types (like 'btrfs').
  Possibly the encryption could also be enabled (for dedicated partitions) e.g. from Kickstart-file (WKS)
  instead of 'LUKS2_ENCRYPT'-flag.
- Check if it's possible to add U-Boot support for Secure boot:
  U-boot is successfully loaded from boot.img, but it cannot load FIT-image.
  U-boot recognizes only boot.img, but cannot extract the content (e.g. no mount-support).
  U-boot can bind files in host-mode (uboot-version running in PC), so perhaps the logic is available...

## Optionally add U-boot

This additional bootloader is nice tool with it's built-in console to interact with the system during the boot process (for e.g. executing memory tests).
If you want to use U-boot, enable it in your build/local.conf:

RPI_USE_U_BOOT = "1"

If you also want to package Linux-kernel, RPI base device tree and overlays into single file called FIT-image (Flattened Image Tree), add also these lines in local conf:

```
  KERNEL_CLASSES += "kernel-fitimage"
  KERNEL_IMAGETYPE = "fitImage"
  KERNEL_BOOTCMD = "bootm"
  UBOOT_SIGN_ENABLE = "0"
  KERNEL_IMAGETYPE_UBOOT = "fitImage"
```

Notice that fitImage has to be loaded into RAM (by 'fatload') far away from, where the content of fitImage will next be loaded to (by 'bootm'). Otherwise when 'bootm' starts to extract kernel (or DTB) from fitImage to RAM, it might starts to overwrite 'fitImage', prohibiting device to boot...
I made a patch to U-boot configuration (under meta_rpilinux/recipes-bsp/u-boot) for getting U-boot to load fitImage to proper area (address defined in SYS_LOAD_ADDR), and for executing it. U-boot is also configured to use FIT-image format.
Commands are simply:

```
fatload mmc 0:1 ${loadaddr} fitImage
bootm ${loadaddr}
```

That's it. 'bootm' will load default configuration (see below), so proper kernel (well, that only available kernel) and DTB. Kernel seems to be loaded to '0x00008000' (see '.its' below) followed by DTB.

fitImage contains device tree and overlay blobs from kernel source tree, not from U-boot tree, so possibly these also have more updated features e.g. for your Raspberry HAT extensions than U-boot DTBs...

If U-boot was not installed, startxx.elf (x is your RaspberryPi board) would boot Linux kernel with the help of 'config.txt', where you set desired DTBs and overlays. When using U-boot, you can also do the selection, by setting desired configurations in 'bootm'-command's parameters.
I didn't test it yet, but in addition to default configuration 'bootm' can give one ore more overlays for the kernel:

```
 bootm ${loadaddr}#fdt-bcm2711-rpi-4-b.dtb#conf-gpio-ir.dtbo
```
Perhaps I'll include later some examples either in U-boot's boot-script, but anyway the loading of overlays can be tested directly in U-boot's console (by first stopping autoboot by pressing any key).

You can find source file for fitImage (after bitbaking) at least from 'tmp/work/raspberrypi4_64-poky-linux/linux-raspberrypi/6.6.63+git/deploy-linux-raspberrypi/fitImage-its-raspberrypi4-64.its', but I also added '.its-file' alongside this Readme-file for investigation.

```
        images {
                kernel-1 {
                        description = "Linux kernel";
                        ...
                        load = <0x00008000>;
                        entry = <0x00008000>;
                };
                fdt-bcm2711-rpi-4-b.dtb {
                        description = "Flattened Device Tree blob";
                        data = /incbin/("arch/arm64/boot/dts/broadcom/bcm2711-rpi-4-b.dtb");
                        ...
                };
                ...
                fdt-gpio-ir.dtbo {
                        description = "Flattened Device Tree blob";
                        data = /incbin/("arch/arm64/boot/dts/overlays/gpio-ir.dtbo");
                    ...
                };
                
        configurations {
                default = "conf-bcm2711-rpi-4-b.dtb";
                conf-bcm2711-rpi-4-b.dtb {
                        description = "1 Linux kernel, FDT blob";
                        kernel = "kernel-1";
                        fdt = "fdt-bcm2711-rpi-4-b.dtb";                       
                        ...
                };
                ...         
                conf-gpio-ir.dtbo {
                        description = "0 FDT blob";
                        fdt = "fdt-gpio-ir.dtbo";
                        ...
                };  
```

