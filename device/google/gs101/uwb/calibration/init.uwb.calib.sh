#!/vendor/bin/sh
if [ -z "$2" ]
then
    echo "usage is $0 input-calibration output_calibration"
    exit 0
fi
OUTPUT_CALIB="$2"
if [ ! -f "$OUTPUT_CALIB" ]; then
    touch $OUTPUT_CALIB
    chmod 0600 $OUTPUT_CALIB
    file="$1"
    while IFS=, read -r f1 f2
    do
        case $f1 in
            "UWB_cal_tx_xtal_reg_final_value")
                if [ $((0x$f2)) -gt 63 ]; then
                    f2=63
                fi
                echo 'xtal_trim=0x'$f2 >> $OUTPUT_CALIB
                ;;
            "UWB_TX1RX1_CH5_tx_ant_delay_cal_data")
                echo 'ant0.ch5.prf64.ant_delay=0x'$f2 >> $OUTPUT_CALIB
                ;;
            "UWB_TX1RX1_CH5_rx_ant_delay_cal_data")
                echo 'ant1.ch5.prf64.ant_delay=0x'$f2 >> $OUTPUT_CALIB
                ;;
            "UWB_TX1RX1_CH9_tx_ant_delay_cal_data")
                echo 'ant0.ch9.prf64.ant_delay=0x'$f2 >> $OUTPUT_CALIB
                ;;
            "UWB_TX1RX1_CH9_rx_ant_delay_cal_data")
                echo 'ant1.ch9.prf64.ant_delay=0x'$f2 >> $OUTPUT_CALIB
                ;;
            "UWB_RX2_CH5_cal_pdoa_data")
                 # float value * 2048
                 #/vendor/bin/sh does not support "bc"
                 base=2048
                 ones=${f2%%.*}
                 dec=${f2#*.}
                 ones="$(($ones*$base))"
                 if [ ${#dec} -eq 2 ]; then
                     dec="$(($dec*$base/100))"
                     echo 'ant1.ant3.ch5.pdoa_offset='$(($ones+$dec)) >> $OUTPUT_CALIB
                 fi
                 ;;
             "UWB_RX2_CH9_cal_pdoa_data")
                 base=2048
                 ones=${f2%%.*}
                 dec=${f2#*.}
                 ones="$(($ones*$base))"
                 if [ ${#dec} -eq 2 ]; then
                     dec="$(($dec*$base/100))"
                     echo 'ant1.ant3.ch9.pdoa_offset='$(($ones+$dec)) >> $OUTPUT_CALIB
                 fi
        esac
    done <"$file"
    exit 0
fi
