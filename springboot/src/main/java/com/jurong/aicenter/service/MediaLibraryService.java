package com.jurong.aicenter.service;

import com.jurong.aicenter.dto.media.CreateLibraryRequest;
import com.jurong.aicenter.dto.media.MediaLibraryResponse;
import com.jurong.aicenter.dto.media.RenameLibraryRequest;

import java.util.List;

public interface MediaLibraryService {

    /**
     * 鍒楀嚭褰撳墠鐢ㄦ埛鎵€鏈夎祫浜у簱锛堝惈 2 涓郴缁熷簱 + custom 搴擄級
     */
    List<MediaLibraryResponse> listLibraries(Long userId);

    /**
     * 鏂板缓 custom 璧勪骇搴擄紙绯荤粺搴撲笉鍙墜鍔ㄥ垱寤猴級
     */
    MediaLibraryResponse createLibrary(Long userId, CreateLibraryRequest request);

    /**
     * 閲嶅懡鍚嶈祫浜у簱锛堢郴缁熷簱涓嶅彲閲嶅懡鍚嶏級
     */
    MediaLibraryResponse renameLibrary(Long userId, Long libraryId, RenameLibraryRequest request);

    /**
     * 鍒犻櫎 custom 璧勪骇搴擄紝杩炲悓搴撳唴绱犳潗 + MinIO 瀵硅薄
     */
    void deleteLibrary(Long userId, Long libraryId);

    /**
     * 娉ㄥ唽鏃惰皟鐢細鍒涘缓 2 涓郴缁熼粯璁ゅ簱锛堜簨鍔″唴锛?
     */
    void createDefaultLibraries(Long userId);

    /**
     * 鎷跨敤鎴风殑"AI 鐢熸垚缁撴灉"搴?
     */
    com.jurong.aicenter.entity.MediaLibrary getAiLibrary(Long userId);

    /**
     * 鎷跨敤鎴风殑"鎴戠殑璧勪骇"搴擄紱娌℃湁鍒欒嚜鍔ㄥ缓涓€涓?
     */
    com.jurong.aicenter.entity.MediaLibrary getOrCreateUploadLibrary(Long userId);
    default com.jurong.aicenter.entity.MediaLibrary getUploadLibrary(Long userId) {
        return getOrCreateUploadLibrary(userId);
    }


    /**
     * 鎷跨敤鎴烽涓?custom 搴擄紙鎸?sort_order 鎺掑簭锛夛紱娌℃湁鍒欏缓涓€涓?"鏈垎绫?
     */
    com.jurong.aicenter.entity.MediaLibrary getOrCreateDefaultCustom(Long userId);
}
