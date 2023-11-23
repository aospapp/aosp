/* Copyright (c) 2012 The Chromium OS Authors. All rights reserved.
 * Use of this source code is governed by a BSD-style license that can be
 * found in the LICENSE file.
 */

#include <assert.h>
#include <stdlib.h>
#include <syslog.h>

#include "audio_thread.h"
#include "cras_apm_list.h"
#include "cras_config.h"
#include "cras_dsp.h"
#include "cras_iodev.h"
#include "cras_iodev_list.h"
#include "cras_messages.h"
#include "cras_observer.h"
#include "cras_rclient.h"
#include "cras_rstream.h"
#include "cras_server_metrics.h"
#include "cras_system_state.h"
#include "cras_types.h"
#include "cras_util.h"
#include "stream_list.h"
#include "utlist.h"

/* An attached client.
 *  id - The id of the client.
 *  fd - Connection for client communication.
 */
struct cras_rclient {
	struct cras_observer_client *observer;
	size_t id;
	int fd;
};

/* Handles a message from the client to connect a new stream */
static int handle_client_stream_connect(struct cras_rclient *client,
					const struct cras_connect_message *msg,
					int aud_fd)
{
	struct cras_rstream *stream;
	struct cras_client_stream_connected stream_connected;
	struct cras_client_stream_connected_old stream_connected_old;
	struct cras_client_message *reply;
	struct cras_audio_format remote_fmt;
	struct cras_rstream_config stream_config;
	int rc;
	int stream_fds[2];

	unpack_cras_audio_format(&remote_fmt, &msg->format);

	/* check the aud_fd is valid. */
	if (aud_fd < 0) {
		syslog(LOG_ERR, "Invalid fd in stream connect.\n");
		rc = -EINVAL;
		goto reply_err;
	}
	/* When full, getting an error is preferable to blocking. */
	cras_make_fd_nonblocking(aud_fd);

	/* Create the stream with the specified parameters. */
	stream_config.stream_id = msg->stream_id;
	stream_config.stream_type = msg->stream_type;
	stream_config.direction = msg->direction;
	stream_config.dev_idx = msg->dev_idx;
	stream_config.flags = msg->flags;
	stream_config.effects = msg->effects;
	stream_config.format = &remote_fmt;
	stream_config.buffer_frames = msg->buffer_frames;
	stream_config.cb_threshold = msg->cb_threshold;
	stream_config.audio_fd = aud_fd;
	stream_config.client = client;
	rc = stream_list_add(cras_iodev_list_get_stream_list(),
			     &stream_config, &stream);
	if (rc) {
		rc = -ENOMEM;
		goto reply_err;
	}

	/* Tell client about the stream setup. */
	syslog(LOG_DEBUG, "Send connected for stream %x\n", msg->stream_id);
	if (msg->proto_version == CRAS_PROTO_VER) {
		cras_fill_client_stream_connected(
				&stream_connected,
				0, /* No error. */
				msg->stream_id,
				&remote_fmt,
				cras_rstream_get_total_shm_size(stream),
				cras_rstream_get_effects(stream));
		reply = &stream_connected.header;
	} else {
		cras_fill_client_stream_connected_old(
				&stream_connected_old,
				0, /* No error. */
				msg->stream_id,
				&remote_fmt,
				cras_rstream_get_total_shm_size(stream));
		reply = &stream_connected_old.header;
	}
	stream_fds[0] = cras_rstream_input_shm_fd(stream);
	stream_fds[1] = cras_rstream_output_shm_fd(stream);
	rc = cras_rclient_send_message(client, reply, stream_fds, 2);
	if (rc < 0) {
		syslog(LOG_ERR, "Failed to send connected messaged\n");
		stream_list_rm(cras_iodev_list_get_stream_list(),
			       stream->stream_id);
		goto reply_err;
	}

	/* Metrics logs the stream configurations. */
	cras_server_metrics_stream_config(&stream_config);

	return 0;

reply_err:
	/* Send the error code to the client. */
	if (msg->proto_version == CRAS_PROTO_VER) {
		cras_fill_client_stream_connected(
				&stream_connected, rc, msg->stream_id,
				&remote_fmt, 0, msg->effects);
		reply = &stream_connected.header;
	} else {
		cras_fill_client_stream_connected_old(
				&stream_connected_old, rc, msg->stream_id,
				&remote_fmt, 0);
		reply = &stream_connected_old.header;
	}
	cras_rclient_send_message(client, reply, NULL, 0);

	if (aud_fd >= 0)
		close(aud_fd);

	return rc;
}

/* Handles messages from the client requesting that a stream be removed from the
 * server. */
static int handle_client_stream_disconnect(
		struct cras_rclient *client,
		const struct cras_disconnect_stream_message *msg)
{
	return stream_list_rm(cras_iodev_list_get_stream_list(),
			      msg->stream_id);
}

/* Handles dumping audio thread debug info back to the client. */
static void dump_audio_thread_info(struct cras_rclient *client)
{
	struct cras_client_audio_debug_info_ready msg;
	struct cras_server_state *state;

	cras_fill_client_audio_debug_info_ready(&msg);
	state = cras_system_state_get_no_lock();
	audio_thread_dump_thread_info(cras_iodev_list_get_audio_thread(),
				      &state->audio_debug_info);
	cras_rclient_send_message(client, &msg.header, NULL, 0);
}

/* Handles dumping audio snapshots to shared memory for the client. */
static void dump_audio_thread_snapshots(struct cras_rclient *client)
{
	struct cras_client_audio_debug_info_ready msg;

	cras_fill_client_audio_debug_info_ready(&msg);
	cras_system_state_dump_snapshots();
	cras_rclient_send_message(client, &msg.header, NULL, 0);
}

static void handle_get_hotword_models(struct cras_rclient *client,
				      cras_node_id_t node_id)
{
	struct cras_client_get_hotword_models_ready *msg;
	char *hotword_models;
	unsigned hotword_models_size;
	uint8_t buf[CRAS_CLIENT_MAX_MSG_SIZE];

	msg = (struct cras_client_get_hotword_models_ready *)buf;
	hotword_models = cras_iodev_list_get_hotword_models(node_id);
	if (!hotword_models)
		goto empty_reply;
	hotword_models_size = strlen(hotword_models);
	if (hotword_models_size + sizeof(*msg) > CRAS_CLIENT_MAX_MSG_SIZE) {
		free(hotword_models);
		goto empty_reply;
	}

	cras_fill_client_get_hotword_models_ready(msg, hotword_models,
						  hotword_models_size);
	cras_rclient_send_message(client, &msg->header, NULL, 0);
	free(hotword_models);
	return;

empty_reply:
	cras_fill_client_get_hotword_models_ready(msg, NULL, 0);
	cras_rclient_send_message(client, &msg->header, NULL, 0);
}

/* Client notification callback functions. */

static void send_output_volume_changed(void *context, int32_t volume)
{
	struct cras_client_volume_changed msg;
	struct cras_rclient *client = (struct cras_rclient *)context;

	cras_fill_client_output_volume_changed(&msg, volume);
	cras_rclient_send_message(client, &msg.header, NULL, 0);
}

static void send_output_mute_changed(void *context, int muted,
				     int user_muted, int mute_locked)
{
	struct cras_client_mute_changed msg;
	struct cras_rclient *client = (struct cras_rclient *)context;

	cras_fill_client_output_mute_changed(&msg, muted,
					     user_muted, mute_locked);
	cras_rclient_send_message(client, &msg.header, NULL, 0);
}

static void send_capture_gain_changed(void *context, int32_t gain)
{
	struct cras_client_volume_changed msg;
	struct cras_rclient *client = (struct cras_rclient *)context;

	cras_fill_client_capture_gain_changed(&msg, gain);
	cras_rclient_send_message(client, &msg.header, NULL, 0);
}

static void send_capture_mute_changed(void *context, int muted, int mute_locked)
{
	struct cras_client_mute_changed msg;
	struct cras_rclient *client = (struct cras_rclient *)context;

	cras_fill_client_capture_mute_changed(&msg, muted, mute_locked);
	cras_rclient_send_message(client, &msg.header, NULL, 0);
}

static void send_nodes_changed(void *context)
{
	struct cras_client_nodes_changed msg;
	struct cras_rclient *client = (struct cras_rclient *)context;

	cras_fill_client_nodes_changed(&msg);
	cras_rclient_send_message(client, &msg.header, NULL, 0);
}

static void send_active_node_changed(void *context,
				     enum CRAS_STREAM_DIRECTION dir,
				     cras_node_id_t node_id)
{
	struct cras_client_active_node_changed msg;
	struct cras_rclient *client = (struct cras_rclient *)context;

	cras_fill_client_active_node_changed(&msg, dir, node_id);
	cras_rclient_send_message(client, &msg.header, NULL, 0);
}

static void send_output_node_volume_changed(void *context,
					    cras_node_id_t node_id,
					    int32_t volume)
{
	struct cras_client_node_value_changed msg;
	struct cras_rclient *client = (struct cras_rclient *)context;

	cras_fill_client_output_node_volume_changed(&msg, node_id, volume);
	cras_rclient_send_message(client, &msg.header, NULL, 0);
}

static void send_node_left_right_swapped_changed(void *context,
						 cras_node_id_t node_id,
						 int swapped)
{
	struct cras_client_node_value_changed msg;
	struct cras_rclient *client = (struct cras_rclient *)context;

	cras_fill_client_node_left_right_swapped_changed(
						&msg, node_id, swapped);
	cras_rclient_send_message(client, &msg.header, NULL, 0);
}

static void send_input_node_gain_changed(void *context,
					 cras_node_id_t node_id,
					 int32_t gain)
{
	struct cras_client_node_value_changed msg;
	struct cras_rclient *client = (struct cras_rclient *)context;

	cras_fill_client_input_node_gain_changed(&msg, node_id, gain);
	cras_rclient_send_message(client, &msg.header, NULL, 0);
}

static void send_num_active_streams_changed(void *context,
					    enum CRAS_STREAM_DIRECTION dir,
					    uint32_t num_active_streams)
{
	struct cras_client_num_active_streams_changed msg;
	struct cras_rclient *client = (struct cras_rclient *)context;

	cras_fill_client_num_active_streams_changed(
					&msg, dir, num_active_streams);
	cras_rclient_send_message(client, &msg.header, NULL, 0);
}

static void register_for_notification(struct cras_rclient *client,
				      enum CRAS_CLIENT_MESSAGE_ID msg_id,
				      int do_register)
{
	struct cras_observer_ops observer_ops;
	int empty;

	cras_observer_get_ops(client->observer, &observer_ops);

	switch (msg_id) {
	case CRAS_CLIENT_OUTPUT_VOLUME_CHANGED:
		observer_ops.output_volume_changed =
			do_register ? send_output_volume_changed : NULL;
		break;
	case CRAS_CLIENT_OUTPUT_MUTE_CHANGED:
		observer_ops.output_mute_changed =
			do_register ? send_output_mute_changed : NULL;
		break;
	case CRAS_CLIENT_CAPTURE_GAIN_CHANGED:
		observer_ops.capture_gain_changed =
			do_register ? send_capture_gain_changed : NULL;
		break;
	case CRAS_CLIENT_CAPTURE_MUTE_CHANGED:
		observer_ops.capture_mute_changed =
			do_register ? send_capture_mute_changed : NULL;
		break;
	case CRAS_CLIENT_NODES_CHANGED:
		observer_ops.nodes_changed =
			do_register ? send_nodes_changed : NULL;
		break;
	case CRAS_CLIENT_ACTIVE_NODE_CHANGED:
		observer_ops.active_node_changed =
			do_register ? send_active_node_changed : NULL;
		break;
	case CRAS_CLIENT_OUTPUT_NODE_VOLUME_CHANGED:
		observer_ops.output_node_volume_changed =
			do_register ? send_output_node_volume_changed : NULL;
		break;
	case CRAS_CLIENT_NODE_LEFT_RIGHT_SWAPPED_CHANGED:
		observer_ops.node_left_right_swapped_changed =
		    do_register ? send_node_left_right_swapped_changed : NULL;
		break;
	case CRAS_CLIENT_INPUT_NODE_GAIN_CHANGED:
		observer_ops.input_node_gain_changed =
			do_register ? send_input_node_gain_changed : NULL;
		break;
	case CRAS_CLIENT_NUM_ACTIVE_STREAMS_CHANGED:
		observer_ops.num_active_streams_changed =
			do_register ? send_num_active_streams_changed : NULL;
		break;
	default:
		syslog(LOG_ERR,
		       "Invalid client notification message ID: %u", msg_id);
		break;
	}

	empty = cras_observer_ops_are_empty(&observer_ops);
	if (client->observer) {
		if (empty) {
			cras_observer_remove(client->observer);
			client->observer = NULL;
		} else {
			cras_observer_set_ops(client->observer, &observer_ops);
		}
	} else if (!empty) {
		client->observer = cras_observer_add(&observer_ops, client);
	}
}

/*
 * Exported Functions.
 */

/* Creates a client structure and sends a message back informing the client that
 * the conneciton has succeeded. */
struct cras_rclient *cras_rclient_create(int fd, size_t id)
{
	struct cras_rclient *client;
	struct cras_client_connected msg;
	int state_fd;

	client = (struct cras_rclient *)calloc(1, sizeof(struct cras_rclient));
	if (!client)
		return NULL;

	client->fd = fd;
	client->id = id;

	cras_fill_client_connected(&msg, client->id);
	state_fd = cras_sys_state_shm_fd();
	cras_rclient_send_message(client, &msg.header, &state_fd, 1);

	return client;
}

/* Removes all streams that the client owns and destroys it. */
void cras_rclient_destroy(struct cras_rclient *client)
{
	cras_observer_remove(client->observer);
	stream_list_rm_all_client_streams(
			cras_iodev_list_get_stream_list(), client);
	free(client);
}

/* Entry point for handling a message from the client.  Called from the main
 * server context. */
int cras_rclient_buffer_from_client(struct cras_rclient *client,
				    const uint8_t *buf,
				    size_t buf_len,
				    int fd) {
	struct cras_server_message *msg = (struct cras_server_message *)buf;

	if (buf_len < sizeof(*msg))
		return -EINVAL;
	if (msg->length != buf_len)
		return -EINVAL;
	cras_rclient_message_from_client(client, msg, fd);
	return 0;
}

static int direction_valid(enum CRAS_STREAM_DIRECTION direction)
{
	return direction < CRAS_NUM_DIRECTIONS &&
		direction != CRAS_STREAM_UNDEFINED;
}

#define MSG_LEN_VALID(msg, type) ((msg)->length >= sizeof(type))

/*
 * Check if client is sending an old version of connect message
 * and converts it to the correct cras_connect_message.
 * Note that this is special check only for libcras transition in
 * clients, from CRAS_PROTO_VER = 1 to 2.
 * TODO(hychao): clean up the check once clients transition is done.
 */
static int is_connect_msg_old(const struct cras_server_message *msg,
			      struct cras_connect_message *cmsg)
{
	struct cras_connect_message_old *old;

	if (!MSG_LEN_VALID(msg, struct cras_connect_message_old))
		return 0;

	old = (struct cras_connect_message_old *)msg;
	if (old->proto_version + 1 != CRAS_PROTO_VER)
		return 0;

	memcpy(cmsg, old, sizeof(*old));
	cmsg->effects = 0;
	return 1;
}

/* Entry point for handling a message from the client.  Called from the main
 * server context. */
int cras_rclient_message_from_client(struct cras_rclient *client,
				     const struct cras_server_message *msg,
				     int fd) {
	struct cras_connect_message cmsg;

	assert(client && msg);

	/* Most messages should not have a file descriptor. */
	switch (msg->id) {
	case CRAS_SERVER_CONNECT_STREAM:
		break;
	case CRAS_SERVER_SET_AEC_DUMP:
		syslog(LOG_ERR, "client msg for APM debug, fd %d", fd);
		break;
	default:
		if (fd != -1) {
			syslog(LOG_ERR,
			       "Message %d should not have fd attached.",
			       msg->id);
			close(fd);
			return -1;
		}
		break;
	}

	switch (msg->id) {
	case CRAS_SERVER_CONNECT_STREAM:
		if (MSG_LEN_VALID(msg, struct cras_connect_message)) {
			handle_client_stream_connect(client,
				(const struct cras_connect_message *)msg, fd);
		} else if (is_connect_msg_old(msg, &cmsg)) {
			handle_client_stream_connect(client, &cmsg, fd);
		} else {
			return -EINVAL;
		}
		break;
	case CRAS_SERVER_DISCONNECT_STREAM:
		if (!MSG_LEN_VALID(msg, struct cras_disconnect_stream_message))
			return -EINVAL;
		handle_client_stream_disconnect(client,
			(const struct cras_disconnect_stream_message *)msg);
		break;
	case CRAS_SERVER_SET_SYSTEM_VOLUME:
		if (!MSG_LEN_VALID(msg, struct cras_set_system_volume))
			return -EINVAL;
		cras_system_set_volume(
			((const struct cras_set_system_volume *)msg)->volume);
		break;
	case CRAS_SERVER_SET_SYSTEM_MUTE:
		if (!MSG_LEN_VALID(msg, struct cras_set_system_mute))
			return -EINVAL;
		cras_system_set_mute(
			((const struct cras_set_system_mute *)msg)->mute);
		break;
	case CRAS_SERVER_SET_USER_MUTE:
		if (!MSG_LEN_VALID(msg, struct cras_set_system_mute))
			return -EINVAL;
		cras_system_set_user_mute(
			((const struct cras_set_system_mute *)msg)->mute);
		break;
	case CRAS_SERVER_SET_SYSTEM_MUTE_LOCKED:
		if (!MSG_LEN_VALID(msg, struct cras_set_system_mute))
			return -EINVAL;
		cras_system_set_mute_locked(
			((const struct cras_set_system_mute *)msg)->mute);
		break;
	case CRAS_SERVER_SET_SYSTEM_CAPTURE_GAIN: {
		const struct cras_set_system_capture_gain *m =
			(const struct cras_set_system_capture_gain *)msg;
		if (!MSG_LEN_VALID(msg, struct cras_set_system_capture_gain))
			return -EINVAL;
		cras_system_set_capture_gain(m->gain);
		break;
	}
	case CRAS_SERVER_SET_SYSTEM_CAPTURE_MUTE:
		if (!MSG_LEN_VALID(msg, struct cras_set_system_mute))
			return -EINVAL;
		cras_system_set_capture_mute(
			((const struct cras_set_system_mute *)msg)->mute);
		break;
	case CRAS_SERVER_SET_SYSTEM_CAPTURE_MUTE_LOCKED:
		if (!MSG_LEN_VALID(msg, struct cras_set_system_mute))
			return -EINVAL;
		cras_system_set_capture_mute_locked(
			((const struct cras_set_system_mute *)msg)->mute);
		break;
	case CRAS_SERVER_SET_NODE_ATTR: {
		const struct cras_set_node_attr *m =
			(const struct cras_set_node_attr *)msg;
		if (!MSG_LEN_VALID(msg, struct cras_set_node_attr))
			return -EINVAL;
		cras_iodev_list_set_node_attr(m->node_id, m->attr, m->value);
		break;
	}
	case CRAS_SERVER_SELECT_NODE: {
		const struct cras_select_node *m =
			(const struct cras_select_node *)msg;
		if (!MSG_LEN_VALID(msg, struct cras_select_node) ||
		    !direction_valid(m->direction))
			return -EINVAL;
		cras_iodev_list_select_node(m->direction, m->node_id);
		break;
	}
	case CRAS_SERVER_ADD_ACTIVE_NODE: {
		const struct cras_add_active_node *m =
			(const struct cras_add_active_node *)msg;
		if (!MSG_LEN_VALID(msg, struct cras_add_active_node) ||
		    !direction_valid(m->direction))
			return -EINVAL;
		cras_iodev_list_add_active_node(m->direction, m->node_id);
		break;
	}
	case CRAS_SERVER_RM_ACTIVE_NODE: {
		const struct cras_rm_active_node *m =
			(const struct cras_rm_active_node *)msg;
		if (!MSG_LEN_VALID(msg, struct cras_rm_active_node) ||
		    !direction_valid(m->direction))
			return -EINVAL;
		cras_iodev_list_rm_active_node(m->direction, m->node_id);
		break;
	}
	case CRAS_SERVER_RELOAD_DSP:
		cras_dsp_reload_ini();
		break;
	case CRAS_SERVER_DUMP_DSP_INFO:
		cras_dsp_dump_info();
		break;
	case CRAS_SERVER_DUMP_AUDIO_THREAD:
		dump_audio_thread_info(client);
		break;
	case CRAS_SERVER_DUMP_SNAPSHOTS:
		dump_audio_thread_snapshots(client);
		break;
	case CRAS_SERVER_ADD_TEST_DEV: {
		const struct cras_add_test_dev *m =
			(const struct cras_add_test_dev *)msg;
		if (!MSG_LEN_VALID(msg, struct cras_add_test_dev))
			return -EINVAL;
		cras_iodev_list_add_test_dev(m->type);
		break;
	}
	case CRAS_SERVER_TEST_DEV_COMMAND: {
		const struct cras_test_dev_command *m =
			(const struct cras_test_dev_command *)msg;
		if (!MSG_LEN_VALID(msg, struct cras_test_dev_command))
			return -EINVAL;
		cras_iodev_list_test_dev_command(
			m->iodev_idx, (enum CRAS_TEST_IODEV_CMD)m->command,
			m->data_len, m->data);
		break;
	}
	case CRAS_SERVER_SUSPEND:
		cras_system_set_suspended(1);
		break;
	case CRAS_SERVER_RESUME:
		cras_system_set_suspended(0);
		break;
	case CRAS_CONFIG_GLOBAL_REMIX: {
		const struct cras_config_global_remix *m =
			(const struct cras_config_global_remix *)msg;
		if (!MSG_LEN_VALID(msg, struct cras_config_global_remix) ||
		    m->num_channels > CRAS_MAX_REMIX_CHANNELS)
			return -EINVAL;
		size_t size_with_coefficients = sizeof(*m) +
			m->num_channels * m->num_channels *
			sizeof(m->coefficient[0]);
		if (size_with_coefficients != msg->length)
			return -EINVAL;
		audio_thread_config_global_remix(
				cras_iodev_list_get_audio_thread(),
				m->num_channels,
				m->coefficient);
		break;
	}
	case CRAS_SERVER_GET_HOTWORD_MODELS: {
		if (!MSG_LEN_VALID(msg, struct cras_get_hotword_models))
			return -EINVAL;
		handle_get_hotword_models(client,
			((const struct cras_get_hotword_models *)msg)->node_id);
		break;
	}
	case CRAS_SERVER_SET_HOTWORD_MODEL: {
		const struct cras_set_hotword_model *m =
			(const struct cras_set_hotword_model *)msg;
		if (!MSG_LEN_VALID(msg, struct cras_set_hotword_model))
			return -EINVAL;
		cras_iodev_list_set_hotword_model(m->node_id,
						  m->model_name);
		break;
	}
	case CRAS_SERVER_REGISTER_NOTIFICATION: {
		const struct cras_register_notification *m =
			(struct cras_register_notification *)msg;
		if (!MSG_LEN_VALID(msg, struct cras_register_notification))
			return -EINVAL;
		register_for_notification(
			client, (enum CRAS_CLIENT_MESSAGE_ID)m->msg_id,
			m->do_register);
		break;
	}
	case CRAS_SERVER_SET_AEC_DUMP: {
		const struct cras_set_aec_dump *m =
			(const struct cras_set_aec_dump *)msg;
		if (!MSG_LEN_VALID(msg, struct cras_set_aec_dump))
			return -EINVAL;
		audio_thread_set_aec_dump(
				cras_iodev_list_get_audio_thread(),
				m->stream_id,
				m->start, fd);
		break;
	}
	case CRAS_SERVER_RELOAD_AEC_CONFIG:
		cras_apm_list_reload_aec_config();
		break;
	default:
		break;
	}

	return 0;
}

/* Sends a message to the client. */
int cras_rclient_send_message(const struct cras_rclient *client,
			      const struct cras_client_message *msg,
			      int *fds,
			      unsigned int num_fds)
{
	return cras_send_with_fds(client->fd, (const void *)msg, msg->length,
				  fds, num_fds);
}

