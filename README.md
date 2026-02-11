# meta_rpilinux
Additional Yocto metalayer for Raspberry Pi for Secure Boot and Secure root file system (LUKS2 encryption).
Older versions than Raspberry 4 don't support secure boot.

I have tested these new recipes in Raspberry PI4, because I don't own other Raspberry devices. Secure eeprom bootloader will need some
update for Yocto-rules for Raspberry PI5 and Compute Modules. Otherwise anything here shouldn't be device-specific.

Also recipe for setting up U-boot (with FIT-image) is included. Unfortunately Secure Boot doesn't work yet,
if U-boot is included into the secure boot-image...

My focus here is mostly issues related in the securing process. So this meta-layer has instructions for creating a just small root-file
system (see rpilinux-image.bb), which herits just 'core-image-minimal.bb'. 

## Introduction to Secure Boot

Secure Boot is a mechanism to secure components responsible for booting the device up (bootloaders, kernel, device-tree,
optionally device-tree overlays and initramfs, configuration files) by the help of asynchronic (public/private) cryptographic keys.
Bootfiles can NOT be ALTERED without knowing the key-pair. Notice that even secured bootfiles (e.g configuration) CAN be READ normally. 
For that reason, if e.g. root-filesystem is encypted, the decryption key (which is actually same as encryption key) 
for decrypting the filesystem cannot be stored even inside the secured boot-image.

Raspberry PI's solution for Secure Boot is to create single image called 'boot.img' including all executables
required to get RPI booted up, so at least:
 'startx.elf', 'config.txt', 'cmdline.txt', 'bcm2711-rpi-x.dtb', 'fixup.dat', 'kernel', DTB overlays, optionally 'initramfs'

See [Raspberry PI4 Boot Security](https://pip-assets.raspberrypi.com/categories/1260-security/documents/RP-004651-WP-2-Raspberry%20Pi%204%20Boot%20Security.pdf?disposition=inline).


'boot.img' is secured by calculating a unique signature (a kind of checksum of the file content), and encrypting
it with a private RSA-key. Secured signature is stored to 'boot.sig' file. EEPROM-bootloader running in RPI target
device calculates the signature of the 'boot.img', opens the corresponding signature from 'boot.sig' with public
RSA-key, and compares those signatures. If signatures match, booting process will continue. Naturally a hacker
could create a new key-pair, but if public key is stored ('fused') to RPI's OTP-memory, it cannot be changed
anymore by hacker's public key. And because private key is unique for single public key, it cannot be changed
either. If someone without knowing the original private key tries to alter some of the boot-files, booting process
will stop. In the future quantum-computer can possible calculate the private key from public key...

Notice that eeprom-bootloader is also secured by same signing principle. The first stage bootloader (ROM
bootloader) can then start the eeprom-bootloader securely at the first place. Secure eeprom-bootloader can then
verify and load the contents from boot.img.

Signed security is only available on the B1/C0 stepping of the BCM2711 SOC. The B0 stepping does not have the RSA public
keys in its ROM that would be needed for the ROM to verify the EEPROM-bootloader.
So with B0-stepping it's always possible to switch back to nonsecure eeprom-bootloader. With newer SOCs, secure eeprom-bootloader and RSA-key can be locked, to avoid to flash a non-secure eeprom bootloader anymore or change RSA-public key. 

I myself unfortunately own only B0 stepping-board.

With 'meta-rpilinux' meta-layer, you can do first steps towards the final secure-boot (creating secured eeprom-bootloader, boot.img and boot.sig), and get the good feeling about, how it works. If you hesitate, you can easily still flash the original non-secure eeprom bootloader, and continue without using 'boot.img' and 'boot.sig'.

'rpi-bootfiles-secure.bb'-recipe contains all rules, where helper scripts from [USB-boot tools](https://github.com/raspberrypi/usbboot/tree/master) are used, like rpi-eeprom-config, rpi-eeprom-digest and rpi-make-boot-image.

Notice that because **SIGNED_BOOT**-flag is set to 1 in '/secure-boot-recovery/boot.conf', eeprom-bootloader accepts only signed boot.img files. 

If **program_pubkey** was set to 1 in 'boot.conf', secure-boot would be locked. Check also from Raspberry PI's manuals for the last steps
(enabling **nRPI_boot**, fusing of public RSA-key in OTP etc). After that there is no 'turn back to non-secure boot'-option anymore (on B1/C0 stepping).

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
Cryptsetup is needed for encrypting a single partition of the file-system (synchronous method).
So this same cryptographic key will be used in target-device for decrypting the partition.
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

Extract public key:
   openssl rsa -in private_sb.pem -pubout -out public_sb.pub

Copy 'private_sb.pem' and 'public_sb.pub' to '...poky/build/conf'-directory
```

Create strong RSA-key (**RSA4096**) and passphrase-key for LUKS2-encryption:

```
RSA-key:
   openssl genrsa 4096 > luks.key

Passphrase:
   dd if=/dev/random bs=32 count=1 of=passphrase_luks.key
   printf "Type here some passphrase for starting the decryption..." > passphrase_luks.key

Copy 'luks.key' and 'passphrase_luks.key' to '...poky/build/conf'-directory
```
RSA-key and passphrase-key are added during the encryption into LUKS2-header.
Both can naturally be used for decrypting the file system in the target-device.
But, because no key-file cannot be stored into the non-encrypted partition (boot)
(it's easy to steal the key(s) from boot-partition by the hacker), the user is advised
to type the passphrase.

You could protect all keys by e.g. setting root-user rights for the keys, except when building an Yocto-image:
```
   e.g. chown root:root 'any key'
```
Cryptsetup-tool is used for the decryption, meaning that InitRamFS (early user space) is needed.

Set correct machine e.g. in your build/local.conf, e.g. for Raspberry PI4:

```
 MACHINE ?= "raspberrypi4-64"
```

and also these settings: 

```
HOSTTOOLS += "mcopy mkfs.fat openssl xxd mren cryptsetup"

RPI_SECURE_BOOT = "1"
RPI_EEPROM_BOOTLOADER_UPDATE = "1"

INITRAMFS_IMAGE = "customized-initramfs"
#INITRAMFS_SCRIPTS = "initramfs-boot-encrypted"

WICVARS:append = " LUKS2_ENCRYPT"
LUKS2_ENCRYPT = "1"
```

HOSTTOOLS-variable publishes the tools installed in your native Linux-distro also in Yocto-environment.
Notice that 'boot.img' can be created even without using 'mcopy', but '/dev/loop'-device is needed,
ending up that Yocto-environment isn't sudoless anymore... See usb-tools: 'rpi-make-boot-image.sh'

RPI_SECURE_BOOT-flag enables the creation of boot.img and signature file.

RPI_EEPROM_BOOTLOADER_UPDATE-flag enables the creation of Secure eeprom-bootloader.
If the update of the bootloader into the Raspberry's EEPROM will be successfull, 
unset the flag before the next builds.

'pieeprom.upd', 'pieeprom.sig' and 'recovery.bin' are created for eeprom-bootloader, and stored during the building
alongside 'boot.img' and 'boot.sig' into the boot-partition.
If ROM-bootloader (after switching RPI's power on) detects 'recovery.bin' from the SD-card, updating of eeprom-bootloader
to a newer version (secured one) starts automatically instead of the bootup process.
Unfortunately there are no good indicators for showing the progress of the update 
(when bootloader is named as 'pieeprom.upd'), unless you have a setup serial connection for debug-messaging.
If the update succeeded, 'recovery.bin' would be automatically renamed to 'RECOVERY.000' for avoiding 
the eternal loop of the updates...

You can also rename 'pieeprom.upd' to 'pieeprom.bin' (from rpi-bootfiles-secure.bb/rpi_secure_eeprom_bootloader and rpilinux-image.bb)
for getting green led flashing rapidly during the flashing (red led to indicate the failure).
But rename (or totally remove) 'recovery.bin' from SD-card manually after successfull flashing...

LUKS2_ENCRYPT-flag enables the encryption of EXTx-partition (containing the root filesystem). This flag has to be appended
to WIC-variables for accessing it in 'poky/script/lib/wic/partition.py'. I made also a receipe ('wic-encrypt-partition.bb') for
patching the encryption-routine into 'partition.py'.

When setting INITRAMFS_IMAGE to "customized-initramfs", InitRamFS with decryption feature is added to the image.
That's because 'customized-initramfs.bb' sets 'INITRAMFS_SCRIPTS'-flag to 'initramfs-boot-encrypted',
causing 'initramfs-boot-encrypted.bb' to call script 'init-boot-encrypted.sh'.

Build the image for Raspberry PI4:
```
 bitbake rpilinux
```

You can list the content of the target-image (untar '.wic.bz2' first).
If LUKS2_ENCRYPT=0:

```
  wic ls tmp/deploy/images/raspberrypi4-64/rpilinux-image-raspberrypi4-64.rootfs-20251215221802.wic
    Num     Start        End          Size      Fstype
     1       4194304    140509183    136314880  fat16
     2     142606336    197132287     54525952  ext4
```
If LUKS2_ENCRYPT=1, partition 2 (ext4) cannot be accessed with list-command.

You can list e.g. FAT16-partition for checking attached bootfiles: 
   wic ls tmp/deploy/images/raspberrypi4-64/rpilinux-image-raspberrypi4-64.rootfs-20251215221802.wic:1

Image can be written to SD-card e.g. with bmaptool (on Debian-distributions: 'sudo apt install bmap-tools')
First after inserting SD-card into PC, check if any partitions (like /dev/mmcblk0p1) are automounted, and unmount those.

Then flash the image:

```
sudo bmaptool -d copy tmp/deploy/images/raspberrypi4-64/rpilinux-image-raspberrypi4-64.rootfs-xxxxx.wic.bz2 /dev/mmcblk0
```

## LUKS2-Encryption/decryption using Cryptsetup

### Encrypting
Partition containing rootfile-system is encrypted during 'bitbaking'.
Cryptsetup is disallowed to ask any confirmation. Otherwise Yocto will complain.
Key-file is used instead of asking passphrase from the user. Also batch-mode is enabled.
To avoid root-accesses, Yocto doesn't use any '/dev'-interface when formatting the EXTx-partition
(and storing file system there). Instead a file (sparse type) is created. But because Cryptsetup uses
'/run'-directory by default, '--disable-locks'-option is needed for allowing Cryptsetup to be executed without 'sudo'.
Encryption is done after, the rootfs-data is written to the partition (by '--reencrypt').
See details in comments from patch-file appended by 'wic-encrypt-partition.bb'.

So rootfs-data has to be shifted for getting some room for LUKS2-header. One resizing of the partition (with resize2fs-tool)
is needed after succesfull encryption, but this is done in decryption-phase (in the Raspberry PI-target). 

### Decrypting
Cryptsetup and resize2fs are used. Because Linux Kernel cannot execute user-space tools, Cryptsetup is executed in Initramfs. 
Default mode is to ask passphrase from the user. So batch-mode has been disabled for Cryptsetup.
Optionally key-file could be used, if it's detected e.g. from USB-stick (not supported yet).
Key-file could be stored into Raspberry PI's OTP memory as well. USB-boot tools contains 'rpi-otp-private-key' script
for storing and reading the key. But it's not supported in this meta-layer, and I'm a bit sceptical,
if OTP-register is a save place for this usage.
If anyone has played with TPM-security chip connected to Raspberry, it's a good place for storing the key too...

resize2fs has to be executed once, to get EXTx-partition (x=2,3,4) adjusted after data-shifting,
which is needed for inserting LUKS2-header in encryption-phase.

### TODO: 
-Raspberry PI5 uses other code-base for secure eeprom-bootloader.
    rpi-bootfiles-secure.bb: recovery.bin and bootloader under the
    '.../secure-boot-recovery5', .../rpi-eeprom/firmware2712
-rules for creating non-secure eeprom bootloader for turning back to non-secured world

-CM:
   - CM5 and newer seems to support -> could bootloader be updated here, like for RPI4/RP5? 
   - CM4 and CM4S don't support automatic updates (ROM-bootloader cannot load recovery.bin from eMMC)
   -> flash manually e.g. with 'rpiboot'-tool 
- LUKS-decryption: detect the luks.key from USB-stick...
- In some point I could offer the LUKS-encryption-function also to Poky's upstream.
  But the encryption-routine could be expanded first to support other partition-types (like 'btrfs').
  Possibly the encryption could also be enabled (for dedicated partitions) e.g. from Kickstart-file (WKS)
  instead of 'LUKS2_ENCRYPT'-flag.

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

