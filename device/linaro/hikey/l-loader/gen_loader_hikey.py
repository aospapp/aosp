#!/usr/bin/env python

import os
import os.path
import sys, getopt
import binascii
import struct
import string

# --------------------------
# | loader | BL1 | NS BL1U |
# --------------------------

class generator(object):
    #
    # struct l_loader_head {
    #      unsigned int	first_instr;
    #      unsigned char	magic[16];	@ BOOTMAGICNUMBER!
    #      unsigned int	l_loader_start;         @ start of loader
    #      unsigned int	l_loader_end;           @ end of BL1 (without ns_bl1u)
    # };
    file_header = [0, 0, 0, 0, 0, 0, 0]

    #
    # struct entry_head {
    #       unsigned char   magic[8];           @ ENTY
    #       unsigned char   name[8];            @ loader/bl1/ns_bl1u
    #       unsigned int    start_lba;
    #       unsigned int    count_lba;
    #       unsigned int    flag;               @ boot partition or not
    # };
    # size of struct entry_head is 28
    #

    s1_entry_name = ['loader', 'bl1', 'ns_bl1u']

    block_size = 512

    # set in self.add()
    idx = 0

    # file pointer
    p_entry = 0        # pointer in header
    p_file = 0         # pointer in file
    p_loader_end = 0   # pointer in header

    def __init__(self, out_img):
        try:
            self.fp = open(out_img, "wb+")
        except IOError, e:
            print "*** file open error:", e
            sys.exit(3)
        else:
            self.entry_hd = [[0 for col in range(7)] for row in range(5)]

    def __del__(self):
        self.fp.close()

    def add(self, lba, fname):
        try:
            fsize = os.path.getsize(fname)
        except IOError, e:
            print "*** file open error:", e
            sys.exit(4)
        else:
            blocks = (fsize + self.block_size - 1) / self.block_size
            # Boot Area1 in eMMC
            bootp = 1
            if self.idx == 0:
                # both loader and bl1.bin locates in l-loader.bin bias 2KB
                self.p_entry = 28
            elif (self.idx > 1):
                # image: ns_bl1u
                # Record the end of loader & BL1. ns_bl1u won't be loaded by BootROM.
                self.p_loader_end = self.p_file
                # ns_bl1u should locates in l-loader.bin bias 2KB too
                if (self.p_file < (lba * self.block_size - 2048)):
                    self.p_file = lba * self.block_size - 2048

            # Maybe the file size isn't aligned. So pad it.
            if (self.idx == 0):
                if fsize > 2048:
                    print 'loader size exceeds 2KB. file size: ', fsize
                    sys.exit(4)
                else:
                    left_bytes = 2048 - fsize
            else:
                left_bytes = fsize % self.block_size
                if left_bytes:
                    left_bytes = self.block_size - left_bytes
            print 'lba: ', lba, 'blocks: ', blocks, 'bootp: ', bootp, 'fname: ', fname
            # write images
            fimg = open(fname, "rb")
            for i in range (0, blocks):
                buf = fimg.read(self.block_size)
                # loader's p_file is 0 at here
                self.fp.seek(self.p_file)
                self.fp.write(buf)
                # p_file is the file pointer of the new binary file
                # At last, it means the total block size of the new binary file
                self.p_file += self.block_size

            if (self.idx == 0):
                self.p_file = 2048
            print 'p_file: ', self.p_file, 'last block is ', fsize % self.block_size, 'bytes', '  tell: ', self.fp.tell(), 'left_bytes: ', left_bytes
            if left_bytes:
                for i in range (0, left_bytes):
                    zero = struct.pack('x')
                    self.fp.write(zero)
                print 'p_file: ', self.p_file, '  pad to: ', self.fp.tell()

            # write entry information at the header
            byte = struct.pack('8s8siii', 'ENTRYHDR', self.s1_entry_name[self.idx], lba, blocks, bootp)
            self.fp.seek(self.p_entry)
            self.fp.write(byte)
            self.p_entry += 28
            self.idx += 1

            fimg.close()

    def hex2(self, data):
        return data > 0 and hex(data) or hex(data & 0xffffffff)

    def end(self):
        self.fp.seek(20)
        start,end = struct.unpack("ii", self.fp.read(8))
        print "start: ", self.hex2(start), 'end: ', self.hex2(end)
        end = start + self.p_loader_end
        print "start: ", self.hex2(start), 'end: ', self.hex2(end)
        self.fp.seek(24)
        byte = struct.pack('i', end)
        self.fp.write(byte)
        self.fp.close()

    def create(self, img_loader, img_bl1, img_ns_bl1u, output_img):
        print '+-----------------------------------------------------------+'
        print ' Input Images:'
        print '     loader:                       ', img_loader
        print '     bl1:                          ', img_bl1
        print '     ns_bl1u:                      ', img_ns_bl1u
        print ' Ouput Image:                      ', output_img
        print '+-----------------------------------------------------------+\n'

        self.stage = 1

        # The first 2KB is reserved
        # The next 2KB is for loader image
        self.add(4, img_loader)
        print 'self.idx: ', self.idx
        # bl1.bin starts from 4KB
        self.add(8, img_bl1)
        if img_ns_bl1u != 0:
            # ns_bl1u.bin starts from 96KB
            self.add(192, img_ns_bl1u)

def main(argv):
    img_ns_bl1u = 0
    try:
        opts, args = getopt.getopt(argv,"ho:",["img_loader=","img_bl1=","img_ns_bl1u="])
    except getopt.GetoptError:
        print 'gen_loader.py -o <l-loader.bin> --img_loader <l-loader> --img_bl1 <bl1.bin> --img_ns_bl1u <ns_bl1u.bin>'
        sys.exit(2)
    for opt, arg in opts:
        if opt == '-h':
            print 'gen_loader.py -o <l-loader.bin> --img_loader <l-loader> --img_bl1 <bl1.bin> --img_ns_bl1u <ns_bl1u.bin>'
            sys.exit(1)
        elif opt == '-o':
            output_img = arg
        elif opt in ("--img_loader"):
            img_loader = arg
        elif opt in ("--img_bl1"):
            img_bl1 = arg
        elif opt in ("--img_ns_bl1u"):
            img_ns_bl1u = arg

    loader = generator(output_img)
    loader.idx = 0

    loader.create(img_loader, img_bl1, img_ns_bl1u, output_img)

    loader.end()

if __name__ == "__main__":
    main(sys.argv[1:])
