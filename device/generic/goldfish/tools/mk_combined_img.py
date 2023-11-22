#!/usr/bin/python
import sys
import os
from subprocess import Popen, PIPE
from tempfile import mkstemp
import argparse
import operator


def check_sparse(filename):
    magic = 3978755898
    with open(filename, 'rb') as i:
        word = i.read(4)
        if magic == int(word[::-1].encode('hex'), 16):
            return True
    return False

def shell_command(comm_list):
    command = Popen(comm_list)
    execute = command.wait()
    if command.returncode != 0:
        sys.exit(1)

def parse_input(input_file):
    parsed_lines = list()
    lines = input_file.readlines()
    for line in lines:
        line = line.strip()
        if not line or line[0] == "#":
            continue
        params = line.split()
        if len(params) == 3:
            for param in params:
                # interprete file paths such as $OUT/system.img
                param = os.path.expandvars(param)
            parsed_lines.append(params)

    partitions = list()
    num_used = set()
    for line in parsed_lines:
        partition_info = dict()
        partition_info["path"] =  line[0]
        partition_info["label"] = line[1]

        try:
            partition_info["num"] = int(line[2])
        except ValueError:
            print "'%s' cannot be converted to int" % (line[2])
            sys.exit(1)

        # check if the partition number is out of range
        if partition_info["num"] > len(lines) or partition_info["num"] < 0:
            print "Invalid partition number: %d, range [1..%d]" % \
                    (partition_info["num"], len(lines))
            sys.exit(1)

        # check if the partition number is duplicated
        if partition_info["num"] in num_used:
            print "Duplicated partition number:%d" % (partition["num"])
            sys.exit(1)
        num_used.add(partition_info["num"])
        partitions.append(partition_info)

    partitions.sort(key=operator.itemgetter("num"))
    return partitions

def write_partition(partition, output_file, offset):
    # $ dd if=/path/to/image of=/path/to/output conv=notrunc,sync \
    #   ibs=1024k obs=1024k seek=<offset>
    dd_comm = ['dd', 'if='+partition["path"], 'of='+output_file,'conv=notrunc,sync',
                'ibs=1024k','obs=1024k', 'seek='+str(offset)]
    shell_command(dd_comm)
    return

def unsparse_partition(partition):
    # if the input image is in sparse format, unsparse it
    simg2img = os.environ.get('SIMG2IMG', 'simg2img')
    print "Unsparsing %s" % (partition["path"]),
    partition["fd"], temp_file = mkstemp()
    shell_command([simg2img, partition["path"], temp_file])
    partition["path"] = temp_file
    print "Done"
    return

def clear_partition_table(filename):
    sgdisk = os.environ.get('SGDISK', 'sgdisk')
    print "%s --clear %s" % (sgdisk, filename)
    shell_command([sgdisk, '--clear', filename])
    return

def add_partition(partition, output_file):
    sgdisk = os.environ.get('SGDISK', 'sgdisk')
    num = str(partition["num"])
    new_comm = '--new='+num+':'+partition["start"]+':'+partition["end"]
    type_comm = '--type='+num+':8300'
    name_comm = '--change-name='+num+':'+partition["label"]
    # build partition table in order. for example:
    # $ sgdisk --new=1:2048:5244927 --type=1:8300 --change-name=1:system \
    #   /path/to/output
    shell_command([sgdisk, new_comm, type_comm, name_comm, output_file])
    return

def main():
    # check usage:
    parser = argparse.ArgumentParser()
    parser.add_argument("-i", "--input",
                        type=str, help="input configuration file",
                        default="image_config")
    parser.add_argument("-o", "--output",
                        type=str, help="output filename",
                        default=os.environ.get("OUT", ".")+"/combined.img")
    args = parser.parse_args()

    output_filename = os.path.expandvars(args.output)

    # check input file
    config_filename = args.input
    if not os.path.exists(config_filename):
        print "Invalid config file name " + config_filename
        sys.exit(1)

    # read input file
    config = open(config_filename, "r")
    partitions = parse_input(config)
    config.close()

    # combine the images
    # add padding
    shell_command(['dd', 'if=/dev/zero', 'of='+output_filename, 'ibs=1024k', 'count=1'])

    for partition in partitions:
        offset = os.path.getsize(output_filename)
        partition["start"] = str(offset / 512)
        # dectect sparse file format
        if check_sparse(partition["path"]):
            unsparse_partition(partition)

        # TODO: extract the partition if the image file is already formatted

        write_partition(partition, output_filename, offset/1024/1024)
        offset = os.path.getsize(output_filename)
        partition["end"] = str(offset / 512 - 1)

    # add padding
    # $ dd if=/dev/zero of=/path/to/output conv=notrunc bs=1 \
    #   count=1024k seek=<offset>
    offset = os.path.getsize(output_filename) / 1024 / 1024
    shell_command(['dd', 'if=/dev/zero', 'of='+output_filename,
                'conv=notrunc', 'bs=1024k', 'count=1', 'seek='+str(offset)])

    # make partition table
    # $ sgdisk --clear /path/to/output
    clear_partition_table(output_filename)

    for partition in partitions:
        add_partition(partition, output_filename)
        # clean up, delete any unsparsed image files generated
        if 'fd' in partition:
            os.close(partition["fd"])
            os.remove(partition["path"])

if __name__ == "__main__":
    main()

