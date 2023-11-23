#!/bin/sh

PERSIST_BLK='/dev/block/by-name/persist'
EFS_BLK='/dev/block/by-name/efs'
MNT_BASE='/mnt/product'
MNT_OLD="$MNT_BASE/convert_old"
MNT_NEW="$MNT_BASE/convert_new"

function log() {
	if [ ! -z "$1" ]; then
		echo "partition_convert: $1" > /dev/kmsg
	fi
}

function check_success() {
	RES=$?
	if [ $RES -ne 0 ]; then
		log "Failed: $1"
	else
		log "Success: $1"
	fi
	return $RES
}

function get_fs_type()
{
	BLOCK=$1
	EXT4_MAGIC=$(xxd $BLOCK -s 0x438 -l 2 -p)
	if [ "$EXT4_MAGIC" = "53ef" ]; then
		 echo "ext4"
	else
		F2FS_MAGIC=$(xxd $BLOCK -s 0x400 -l 4 -p)
		if [ "$F2FS_MAGIC" = "1020f5f2" ]; then
			echo "f2fs"
		else
			echo "unknown"
		fi
	fi
}

# Flow:
# 1. If persist is f2fs we need to make efs ext4 and copy out the files. Once files are copied
# successfully, format persist as ext4 to mark completion of step.
# 2. If persist is ext4 and efs is ext4, we need to copy from efs to persist (use dd). Once
# everything is copied successfully, erase efs to allow it to be formatted to f2fs later.
# 3. If persist is ext4 and efs is not ext4, we have already migrated - do nothing.

# If persist is already ext4 and efs is not ext4 we have already migrated.
PERSIST_FS=$(get_fs_type $PERSIST_BLK)
EFS_FS=$(get_fs_type $EFS_BLK)
if [ "$PERSIST_FS" = "ext4" ]; then
	if [ "$EFS_FS" != "ext4" ]; then
		log "persist ext4 migration already done"
		exit 0
	fi
fi

if [ "$PERSIST_FS" = "unknown" ]; then
	log "persist partition hasn't been initialized"
	exit 0
fi

RETRIES=10
while [[ $RETRIES -gt 0 ]]; do
	# Sleep for 1 second here, as other failure points will trigger continue
	sleep 1
	RETRIES=$((RETRIES-1))

	# If persist is still f2fs, we need to copy to efs.
	if [ "$PERSIST_FS" = "f2fs" ]; then
		# Format efs as ext4
		/system/bin/mke2fs -t ext4 -b 4096 $EFS_BLK
		check_success "/system/bin/mke2fs -t ext4 -b 4096 $EFS_BLK"
		if [ $? -ne 0 ]; then
			continue
		fi

		#Create directory to mount persist partition
		mkdir -p $MNT_OLD
		check_success "mkdir $MNT_OLD"
		if [ $? -ne 0 ]; then
			continue
		fi

		# Create directory to mount efs partition
		mkdir -p $MNT_NEW
		check_success "mkdir $MNT_NEW"
		if [ $? -ne 0 ]; then
			rm -rf $MNT_OLD
			continue
		fi

		# Mount persist
		mount -t f2fs $PERSIST_BLK $MNT_OLD
		check_success "mount -t f2fs $PERSIST_BLK $MNT_OLD"
		if [ $? -ne 0 ]; then
			rm -rf $MNT_NEW
			rm -rf $MNT_OLD
			continue
		fi

		# Mount efs
		mount -t ext4 $EFS_BLK $MNT_NEW
		check_success "mount -t ext4 $EFS_BLK $MNT_NEW"
		if [ $? -ne 0 ]; then
			umount $MNT_OLD
			rm -rf $MNT_NEW
			rm -rf $MNT_OLD
			continue
		fi

		cp -rp $MNT_OLD/.* $MNT_NEW/
		cp -rp $MNT_OLD/* $MNT_NEW/
		check_success "cp -rp $MNT_OLD/* $MNT_NEW/"
		if [ $? -ne 0 ]; then
			umount $MNT_NEW
			umount $MNT_OLD
			rm -rf $MNT_NEW
			rm -rf $MNT_OLD
			continue
		fi

		# Calculate md5sum of all files and compare between persist and efs
		(cd $MNT_NEW; find . -type f | xargs md5sum | sort) > $MNT_BASE/new.md5sums
		(cd $MNT_OLD; find . -type f | xargs md5sum | sort) > $MNT_BASE/old.md5sums
		diff $MNT_BASE/new.md5sums $MNT_BASE/old.md5sums
		check_success "diff $MNT_BASE/new.md5sums $MNT_BASE/old.md5sums"
		RES=$?
		rm $MNT_BASE/new.md5sums $MNT_BASE/old.md5sums

		umount $MNT_NEW
		umount $MNT_OLD
		rm -rf $MNT_NEW
		rm -rf $MNT_OLD

		if [ $RES -ne 0 ]; then
			continue
		fi

		/system/bin/mke2fs -t ext4 -b 4096 $PERSIST_BLK
		check_success "/system/bin/mke2fs -t ext4 -b 4096 $PERSIST_BLK"
		if [ $? -ne 0 ]; then
			continue
		fi

		PERSIST_FS="ext4"
	fi

	# copy efs to persist
	dd if=$EFS_BLK of=$PERSIST_BLK
	check_success "dd if=$EFS_BLK of=$PERSIST_BLK"
	if [ $? -ne 0 ]; then
		continue
	fi

	sync
	check_success "sync"
	if [ $? -ne 0 ]; then
		continue
	fi

	# compare md5sum for integrity
	EFS_MD5SUM=$(dd if=$EFS_BLK 2>/dev/null | md5sum)
	PERSIST_MD5SUM=$(dd if=$PERSIST_BLK 2>/dev/null | md5sum)
	if [ "$PERSIST_MD5SUM" != "$EFS_MD5SUM" ]; then
		log "dd md5sum mismatch"
		continue
	fi

	dd if=/dev/zero of=$EFS_BLK bs=1M count=64
	check_success "dd if=/dev/zero of=$EFS_BLK bs=1M count=64"
	if [ $? -ne 0 ]; then
		continue
	fi

	log "Migration succeeded"
	break
done
