# Add pixel common soong config here.
# Set the variables properly in ProductConfig Makefiles for each pixel device

#For sensor
$(call soong_config_set,sensor,enable_sensor_ssc_for_soong,$(ENABLE_SENSOR_SSC_FOR_SOONG))
