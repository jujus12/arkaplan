package com.arkaplan.app;

import android.view.Surface;

interface IVdService {
    // Destroy method defined by Shizuku server
    void destroy() = 16777114;

    // Returns "OK:<displayId>:<flags>" or "HATA: ..."
    String createDisplay(in Surface surface, int width, int height, int dpi) = 1;
    void setSurface(in Surface surface) = 2;
    oneway void touch(int displayId, int action, float x, float y, long downTime) = 3;
    void release() = 4;
    String lastError() = 5;
}
