package com.example.cleanrecovery.music.data;

/** Metadata for a read-only Kugou cloud playlist. */
public class RemotePlaylist {
    public String id;
    public String globalCollectionId;
    public String listId;
    public String name;
    public String coverUrl;
    public int songCount;
    public String ownerName;

    public String playableId() {
        if (listId != null && !listId.isEmpty()) return listId;
        if (globalCollectionId != null && !globalCollectionId.isEmpty()) return globalCollectionId;
        return id;
    }
}
