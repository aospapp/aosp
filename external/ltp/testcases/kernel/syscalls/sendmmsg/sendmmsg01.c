/*
 * This test is based on source contained in the man pages for sendmmsg and
 * recvmmsg in release 4.15 of the Linux man-pages project.
 */

#define _GNU_SOURCE
#include <netinet/ip.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/types.h>
#include <sys/socket.h>

#include "tst_test.h"
#include "tst_safe_macros.h"
#include "tst_safe_pthread.h"

#define BUFSIZE 16
#define VLEN 2

static void *sender_thread(LTP_ATTRIBUTE_UNUSED void *arg)
{
	struct sockaddr_in addr;
	struct mmsghdr msg[2];
	struct iovec msg1[2], msg2;
	int send_sockfd;
	int retval;

	send_sockfd = SAFE_SOCKET(AF_INET, SOCK_DGRAM, 0);

	addr.sin_family = AF_INET;
	addr.sin_addr.s_addr = htonl(INADDR_LOOPBACK);
	addr.sin_port = htons(1234);
	SAFE_CONNECT(send_sockfd, (struct sockaddr *) &addr, sizeof(addr));

	memset(msg1, 0, sizeof(msg1));
	msg1[0].iov_base = "one";
	msg1[0].iov_len = 3;
	msg1[1].iov_base = "two";
	msg1[1].iov_len = 3;

	memset(&msg2, 0, sizeof(msg2));
	msg2.iov_base = "three";
	msg2.iov_len = 5;

	memset(msg, 0, sizeof(msg));
	msg[0].msg_hdr.msg_iov = msg1;
	msg[0].msg_hdr.msg_iovlen = 2;

	msg[1].msg_hdr.msg_iov = &msg2;
	msg[1].msg_hdr.msg_iovlen = 1;

	retval = sendmmsg(send_sockfd, msg, 2, 0);
	if (retval < 0)
		tst_brk(TFAIL|TTERRNO, "sendmmsg failed");

	return NULL;
}


static void *receiver_thread(LTP_ATTRIBUTE_UNUSED void *arg)
{
	int receive_sockfd;
	struct sockaddr_in addr;
	struct mmsghdr msgs[VLEN];
	struct iovec iovecs[VLEN];
	char bufs[VLEN][BUFSIZE+1];
	struct timespec timeout;
	int i, retval;

	receive_sockfd = SAFE_SOCKET(AF_INET, SOCK_DGRAM, 0);
	addr.sin_family = AF_INET;
	addr.sin_addr.s_addr = htonl(INADDR_LOOPBACK);
	addr.sin_port = htons(1234);
	SAFE_BIND(receive_sockfd, (struct sockaddr *)&addr, sizeof(addr));

	memset(msgs, 0, sizeof(msgs));
	for (i = 0; i < VLEN; i++) {
		iovecs[i].iov_base = bufs[i];
		iovecs[i].iov_len = BUFSIZE;
		msgs[i].msg_hdr.msg_iov = &iovecs[i];
		msgs[i].msg_hdr.msg_iovlen = 1;
	}

	timeout.tv_sec = 1;
	timeout.tv_nsec = 0;

	retval = recvmmsg(receive_sockfd, msgs, VLEN, 0, &timeout);
	if (retval == -1)
		tst_brk(TFAIL | TTERRNO, "recvmmsg failed");
	if (retval != 2)
		tst_brk(TFAIL, "Received unexpected number of messages (%d)",
			retval);

	bufs[0][msgs[0].msg_len] = 0;
	if (strcmp(bufs[0], "onetwo"))
		tst_res(TFAIL, "Error in first received message.");
	else
		tst_res(TPASS, "First message received successfully.");

	bufs[1][msgs[1].msg_len] = 0;
	if (strcmp(bufs[1], "three"))
		tst_res(TFAIL, "Error in second received message.");
	else
		tst_res(TPASS, "Second message received successfully.");

	return NULL;
}

static void run(void)
{
	pthread_t sender;
	pthread_t receiver;

	SAFE_PTHREAD_CREATE(&sender, NULL, sender_thread, NULL);
	SAFE_PTHREAD_CREATE(&receiver, NULL, receiver_thread, NULL);
	SAFE_PTHREAD_JOIN(sender, NULL);
	SAFE_PTHREAD_JOIN(receiver, NULL);
}

static struct tst_test test = {
	.test_all = run,
};
