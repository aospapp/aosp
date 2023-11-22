BL1=bl1.bin
NS_BL1U=BL33_AP_UEFI.fd
PTABLE_LST:=aosp-32g aosp-32g-spare linux-32g

.PHONY: all
all: l-loader.bin prm_ptable.img

l-loader.bin: $(BL1) $(NS_BL1U)
	python gen_loader_hikey960.py -o $@ --img_bl1=$(BL1) --img_ns_bl1u=$(NS_BL1U)

prm_ptable.img:
	for ptable in $(PTABLE_LST); do \
		PTABLE=$${ptable} SECTOR_SIZE=4096 SGDISK=./sgdisk bash -x generate_ptable.sh;\
		cp prm_ptable.img ptable-$${ptable}.img;\
	done

.PHONY: clean
clean:
	rm -f *.img l-loader.bin
