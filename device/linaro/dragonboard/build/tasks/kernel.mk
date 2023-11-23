ifneq ($(filter db845c db845c_mini, $(TARGET_DEVICE)),)

$(PRODUCT_OUT)/dtb.img: $(TARGET_KERNEL_DIR)/sdm845-db845c.dtb $(wildcard $(TARGET_KERNEL_DIR)/qrb5165-rb5.dtb)
	cat $^ > $@

droidcore: $(PRODUCT_OUT)/dtb.img

endif
