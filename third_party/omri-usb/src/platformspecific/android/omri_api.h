/*
 * This file is a part of IRT DAB library (modified for DABtuner).
 *
 * This library is free software; you can redistribute it and/or
 * modify it under the terms of the GNU Lesser General Public
 * License as published by the Free Software Foundation; either
 * version 2.1 of the License, or (at your option) any later version.
 *
 * This library is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 * Lesser General Public License for more details.
 *
 */

/*
 * Plain C interface to the library, so that applications link against it without
 * depending on its C++ classes and the library can be rebuilt and swapped independently.
 */

#ifndef OMRI_API_H
#define OMRI_API_H

#include <stddef.h>
#include <stdint.h>

#ifdef __cplusplus
extern "C" {
#endif

#define OMRI_API __attribute__((visibility("default")))

enum {
    OMRI_STATE_READY = 0,
    OMRI_STATE_FAILED = 1,
    OMRI_STATE_SCANNING = 4
};

/* Announcement type bits (ETSI EN 300 401 clause 8.1.6), used by the announcement flags. */
enum {
    OMRI_ANNOUNCEMENT_ALARM = 1 << 0,
    OMRI_ANNOUNCEMENT_ROAD_TRAFFIC = 1 << 1,
    OMRI_ANNOUNCEMENT_TRANSPORT = 1 << 2,
    OMRI_ANNOUNCEMENT_WARNING = 1 << 3,
    OMRI_ANNOUNCEMENT_NEWS = 1 << 4,
    OMRI_ANNOUNCEMENT_WEATHER = 1 << 5
};

enum {
    OMRI_CODEC_NONE = -1,
    OMRI_CODEC_MPEG_LAYER2 = 0,
    OMRI_CODEC_HE_AAC = 63
};

typedef struct {
    uint32_t service_id;
    uint16_t ensemble_id;
    uint8_t ensemble_ecc;
    uint32_t frequency_khz;
    /* UTF-8, NUL terminated, valid for the duration of the callback. */
    const char* label;
    const char* short_label;
    const char* ensemble_label;
    int programme_type;
    int is_programme_service;
    /* OMRI_CODEC_* of the primary audio component. */
    int audio_codec;
    /* Subchannel bitrate of the primary audio component in kbit/s, 0 if unknown. */
    int bitrate_kbps;
    /* Subchannel of the primary audio component, -1 if unknown. */
    int subchannel_id;
    /*
     * Announcement support (FIG 0/18) known when the ensemble was collected: OMRI_ANNOUNCEMENT_*
     * bits and the clusters the service belongs to. Later changes arrive through
     * omri_callbacks.announcement_support.
     */
    uint16_t announcement_flags;
    const uint8_t* cluster_ids;
    size_t cluster_count;
} omri_service_info;

/*
 * Notifications. Every callback runs on one of the library's worker threads, never on the
 * thread that called into the library, and must not call omri_tuner_close().
 */
typedef struct {
    void* user;
    void (*state)(void* user, int state);
    void (*scan_progress)(void* user, int channel_index, int channel_count);
    void (*service_found)(void* user, const omri_service_info* info);
    void (*service_started)(void* user, uint32_t service_id);
    void (*service_stopped)(void* user, uint32_t service_id);
    /* antenna_level is 0 (worst) .. 6 (best). */
    void (*reception)(void* user, int locked, int antenna_level);
    /*
     * One encoded frame: an MPEG Layer II frame or a DAB+ AAC access unit (960 samples per channel
     * at the AAC core rate). sample_rate is the output rate (48000 or 32000 for DAB+); with sbr set
     * the core runs at half of it.
     */
    void (*audio)(void* user, const uint8_t* data, size_t length, int codec, int channels, int sample_rate, int sbr, int ps);
    void (*dynamic_label)(void* user, const char* utf8);
    void (*slideshow)(void* user, const uint8_t* data, size_t length, int content_sub_type);
    /* Announcement support (FIG 0/18) of a service in the tuned ensemble, when first seen or changed. */
    void (*announcement_support)(void* user, uint16_t ensemble_id, uint32_t service_id, uint16_t flags, const uint8_t* cluster_ids, size_t cluster_count);
    /*
     * Announcement switching (FIG 0/19) in the tuned ensemble: an announcement of the flagged types
     * is on air in subchannel_id for the services of cluster_id. active is 0 (and flags 0) when it
     * ends; ends may be reported more than once.
     */
    void (*announcement)(void* user, uint8_t cluster_id, uint16_t flags, uint8_t subchannel_id, int active);
} omri_callbacks;

typedef struct omri_tuner omri_tuner;

OMRI_API const char* omri_version(void);

/* Routes the library's diagnostic output to logcat (tag "omri"). */
OMRI_API void omri_set_logging(int enabled);

/*
 * Takes over a usbfs descriptor whose interface is already claimed, checks that a supported
 * tuner answers, and starts initialising it. Returns NULL if the device does not respond;
 * info (if not NULL) receives a short diagnostic either way. The descriptor stays owned by
 * the caller and must remain open until omri_tuner_close() returns.
 */
OMRI_API omri_tuner* omri_tuner_open(int fd, const char* device_name, const omri_callbacks* callbacks, char* info, size_t info_length);

/* Stops all worker threads; no callback runs after this returns. */
OMRI_API void omri_tuner_close(omri_tuner* tuner);

OMRI_API void omri_tuner_start_scan(omri_tuner* tuner);
OMRI_API void omri_tuner_stop_scan(omri_tuner* tuner);
OMRI_API void omri_tuner_start_service(omri_tuner* tuner, uint32_t frequency_khz, uint16_t ensemble_id, uint8_t ensemble_ecc, uint32_t service_id);
OMRI_API void omri_tuner_stop_service(omri_tuner* tuner);

#ifdef __cplusplus
}
#endif

#endif //OMRI_API_H
