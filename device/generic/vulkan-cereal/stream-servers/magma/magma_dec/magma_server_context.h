// Generated Code - DO NOT EDIT !!
// generated by 'emugen'
#ifndef __magma_server_context_t_h
#define __magma_server_context_t_h

#include "magma_server_proc.h"

#include "magma_types.h"


struct magma_server_context_t {

	magma_device_import_server_proc_t magma_device_import;
	magma_device_release_server_proc_t magma_device_release;
	magma_device_query_server_proc_t magma_device_query;
	magma_device_create_connection_server_proc_t magma_device_create_connection;
	magma_connection_release_server_proc_t magma_connection_release;
	magma_connection_get_error_server_proc_t magma_connection_get_error;
	magma_connection_create_context_server_proc_t magma_connection_create_context;
	magma_connection_release_context_server_proc_t magma_connection_release_context;
	magma_connection_create_buffer_server_proc_t magma_connection_create_buffer;
	magma_connection_release_buffer_server_proc_t magma_connection_release_buffer;
	magma_connection_import_buffer_server_proc_t magma_connection_import_buffer;
	magma_connection_create_semaphore_server_proc_t magma_connection_create_semaphore;
	magma_connection_release_semaphore_server_proc_t magma_connection_release_semaphore;
	magma_connection_import_semaphore_server_proc_t magma_connection_import_semaphore;
	magma_connection_perform_buffer_op_server_proc_t magma_connection_perform_buffer_op;
	magma_connection_map_buffer_server_proc_t magma_connection_map_buffer;
	magma_connection_unmap_buffer_server_proc_t magma_connection_unmap_buffer;
	magma_connection_execute_command_server_proc_t magma_connection_execute_command;
	magma_connection_execute_immediate_commands_server_proc_t magma_connection_execute_immediate_commands;
	magma_connection_flush_server_proc_t magma_connection_flush;
	magma_connection_get_notification_channel_handle_server_proc_t magma_connection_get_notification_channel_handle;
	magma_connection_read_notification_channel_server_proc_t magma_connection_read_notification_channel;
	magma_buffer_clean_cache_server_proc_t magma_buffer_clean_cache;
	magma_buffer_set_cache_policy_server_proc_t magma_buffer_set_cache_policy;
	magma_buffer_get_cache_policy_server_proc_t magma_buffer_get_cache_policy;
	magma_buffer_set_name_server_proc_t magma_buffer_set_name;
	magma_buffer_get_info_server_proc_t magma_buffer_get_info;
	magma_buffer_get_handle_server_proc_t magma_buffer_get_handle;
	magma_buffer_export_server_proc_t magma_buffer_export;
	magma_semaphore_signal_server_proc_t magma_semaphore_signal;
	magma_semaphore_reset_server_proc_t magma_semaphore_reset;
	magma_semaphore_export_server_proc_t magma_semaphore_export;
	magma_poll_server_proc_t magma_poll;
	magma_initialize_tracing_server_proc_t magma_initialize_tracing;
	magma_initialize_logging_server_proc_t magma_initialize_logging;
	magma_connection_enable_performance_counter_access_server_proc_t magma_connection_enable_performance_counter_access;
	magma_connection_enable_performance_counters_server_proc_t magma_connection_enable_performance_counters;
	magma_connection_create_performance_counter_buffer_pool_server_proc_t magma_connection_create_performance_counter_buffer_pool;
	magma_connection_release_performance_counter_buffer_pool_server_proc_t magma_connection_release_performance_counter_buffer_pool;
	magma_connection_add_performance_counter_buffer_offsets_to_pool_server_proc_t magma_connection_add_performance_counter_buffer_offsets_to_pool;
	magma_connection_remove_performance_counter_buffer_from_pool_server_proc_t magma_connection_remove_performance_counter_buffer_from_pool;
	magma_connection_dump_performance_counters_server_proc_t magma_connection_dump_performance_counters;
	magma_connection_clear_performance_counters_server_proc_t magma_connection_clear_performance_counters;
	magma_connection_read_performance_counter_completion_server_proc_t magma_connection_read_performance_counter_completion;
	magma_virt_connection_create_image_server_proc_t magma_virt_connection_create_image;
	magma_virt_connection_get_image_info_server_proc_t magma_virt_connection_get_image_info;
	virtual ~magma_server_context_t() {}
	int initDispatchByName( void *(*getProc)(const char *name, void *userData), void *userData);
};

#endif
