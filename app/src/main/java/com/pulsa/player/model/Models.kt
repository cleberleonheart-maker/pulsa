package com.pulsa.player.model

data class Song(
    val id: Long,
    val title: String,
    val artist: String,
    val album: String,
    val albumId: Long,
    val durationMs: Long,
    val path: String,
    val year: Int
)

data class Album(
    val id: Long,
    val name: String,
    val artist: String,
    val numSongs: Int
)

data class Artist(
    val name: String,
    val numSongs: Int,
    val numAlbums: Int
)

data class Playlist(
    val id: Long,
    val name: String,
    val count: Int,
    val autoAdd: Boolean = false,
    val system: Boolean = false
)

data class Video(
    val id: Long,
    val title: String,
    val durationMs: Long,
    val path: String,
    val sizeBytes: Long
)

data class SongMeta(
    val songId: Long,
    val title: String,
    val artist: String,
    val album: String
)