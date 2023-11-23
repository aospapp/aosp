# Add pixel common soong config here.
# Set the variables properly in ProductConfig Makefiles for each pixel device

#For sensor
$(call soong_config_set,sensor,mag_no_temp,true)
$(call soong_config_set,sensor,single_light,true)
