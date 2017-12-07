import android.graphics.Bitmap;
import java.io.InputStream;
import java.util.Vector;

public class GifDecoder {

    /**
     * 各帧静态图对象
     */
    public static class GifFrame {
        public Bitmap image;//静态图Bitmap
        public int delay;//图像延迟时间

        public GifFrame(Bitmap im, int del) {
            image = im;
            delay = del;
        }
    }

    public static final int STATUS_OK = 0;//解码成功
    public static final int STATUS_FORMAT_ERROR = 1;//格式错误
    public static final int STATUS_OPEN_ERROR = 2;//打开图片失败

    protected int status;//解码状态
    protected InputStream in;

    protected int width;//完整的GIF图像宽度
    protected int height;//完整的GIF图像高度
    protected boolean gctFlag;//是否使用了全局颜色列表
    protected int gctSize; //全局颜色列表大小
    protected int loopCount = 1; // iterations; 0 = repeat forever

    protected int[] gct; //全局颜色列表
    protected int[] lct; //局部颜色列表
    protected int[] act; //当前使用的颜色列表

    protected int bgIndex; //背景颜色索引
    protected int bgColor; //背景颜色
    protected int lastBgColor; // previous bg color
    protected int pixelAspect; //像素宽高比(Pixel Aspect Radio)

    protected boolean lctFlag;//局部颜色列表标志(Local Color Table Flag)
    protected boolean interlace;//交织标志(Interlace Flag)
    protected int lctSize;//局部颜色列表大小(Size of Local Color Table)

    protected int ix, iy, iw, ih; //当前帧图像的xy偏移量及宽高
    protected int lrx, lry, lrw, lrh;
    protected Bitmap image; // current frame
    protected Bitmap lastImage; // previous frame
    protected int frameindex = 0;

    public int getFrameindex() {
        return frameindex;
    }

    public void setFrameindex(int frameindex) {
        this.frameindex = frameindex;
        if (frameindex > frames.size() - 1) {
            frameindex = 0;
        }
    }

    protected byte[] block = new byte[256]; // current data block
    protected int blockSize = 0; //扩展块大小

    // last graphic control extension info
    protected int dispose = 0;
    // 0=no action; 1=leave in place; 2=restore to bg; 3=restore to prev
    protected int lastDispose = 0;
    protected boolean transparency = false;//是否使用透明色
    protected int delay = 0;//延迟时间(毫秒)
    protected int transIndex;//透明色索引

    protected static final int MaxStackSize = 4096;
    // max decoder pixel stack size

    // LZW decoder working arrays
    protected short[] prefix;
    protected byte[] suffix;
    protected byte[] pixelStack;
    protected byte[] pixels;

    protected Vector<GifFrame> frames;// 存放各帧对象的数组
    protected int frameCount;//帧数

    // to get its Width / Height
    public int getWidth() {
        return width;
    }

    public int getHeigh() {
        return height;
    }

    /**
     * Gets display duration for specified frame.
     *
     * @param n
     *            int index of frame
     * @return delay in milliseconds
     */
    public int getDelay(int n) {
        delay = -1;
        if ((n >= 0) && (n < frameCount)) {
            delay = ((GifFrame) frames.elementAt(n)).delay;
        }
        return delay;
    }

    public int getFrameCount() {
        return frameCount;
    }

    public Bitmap getImage() {
        return getFrame(0);
    }

    public int getLoopCount() {
        return loopCount;
    }

    protected void setPixels() {
        int[] dest = new int[width * height];
        // fill in starting image contents based on last image's dispose code
        if (lastDispose > 0) {
            if (lastDispose == 3) {
                // use image before last
                int n = frameCount - 2;
                if (n > 0) {
                    lastImage = getFrame(n - 1);
                } else {
                    lastImage = null;
                }
            }
            if (lastImage != null) {
                lastImage.getPixels(dest, 0, width, 0, 0, width, height);
                // copy pixels
                if (lastDispose == 2) {
                    // fill last image rect area with background color
                    int c = 0;
                    if (!transparency) {
                        c = lastBgColor;
                    }
                    for (int i = 0; i < lrh; i++) {
                        int n1 = (lry + i) * width + lrx;
                        int n2 = n1 + lrw;
                        for (int k = n1; k < n2; k++) {
                            dest[k] = c;
                        }
                    }
                }
            }
        }

        // copy each source line to the appropriate place in the destination
        int pass = 1;
        int inc = 8;
        int iline = 0;
        for (int i = 0; i < ih; i++) {
            int line = i;
            if (interlace) {
                if (iline >= ih) {
                    pass++;
                    switch (pass) {
                        case 2:
                            iline = 4;
                            break;
                        case 3:
                            iline = 2;
                            inc = 4;
                            break;
                        case 4:
                            iline = 1;
                            inc = 2;
                    }
                }
                line = iline;
                iline += inc;
            }
            line += iy;
            if (line < height) {
                int k = line * width;
                int dx = k + ix; // start of line in dest
                int dlim = dx + iw; // end of dest line
                if ((k + width) < dlim) {
                    dlim = k + width; // past dest edge
                }
                int sx = i * iw; // start of line in source
                while (dx < dlim) {
                    // map color and insert in destination
                    int index = ((int) pixels[sx++]) & 0xff;
                    int c = act[index];
                    if (c != 0) {
                        dest[dx] = c;
                    }
                    dx++;
                }
            }
        }
        image = Bitmap.createBitmap(dest, width, height, Bitmap.Config.RGB_565);
    }

    public Bitmap getFrame(int n) {
        Bitmap im = null;
        if ((n >= 0) && (n < frameCount)) {
            im = ((GifFrame) frames.elementAt(n)).image;
        }
        return im;
    }

    public GifFrame[] getFrames() {
        if (null != frames)
            return frames.toArray(new GifFrame[0]);
        return null;
    }

    public Bitmap nextBitmap() {
        frameindex++;
        if (frameindex > frames.size() - 1) {
            frameindex = 0;
        }
        return ((GifFrame) frames.elementAt(frameindex)).image;
    }

    public int nextDelay() {
        return ((GifFrame) frames.elementAt(frameindex)).delay;
    }

    /**
     * 解码入口，读取GIF图片输入流
     * @param is
     * @return
     */
    public int read(InputStream is) {
        init();
        if (is != null) {
            in = is;
            readHeader();
            if (!err()) {
                readContents();
                if (frameCount < 0) {
                    status = STATUS_FORMAT_ERROR;
                }
            }
        } else {
            status = STATUS_OPEN_ERROR;
        }
        try {
            is.close();
        } catch (Exception e) {
            e.printStackTrace();
        }
        return status;
    }

    /**
     * 解码图像数据
     */
    protected void decodeImageData() {
        int NullCode = -1;
        int npix = iw * ih;
        int available, clear, code_mask, code_size, end_of_information, in_code, old_code, bits, code, count, i, datum, data_size, first, top, bi, pi;

        if ((pixels == null) || (pixels.length < npix)) {
            pixels = new byte[npix]; // allocate new pixel array
        }
        if (prefix == null) {
            prefix = new short[MaxStackSize];
        }
        if (suffix == null) {
            suffix = new byte[MaxStackSize];
        }
        if (pixelStack == null) {
            pixelStack = new byte[MaxStackSize + 1];
        }
        // Initialize GIF data stream decoder.
        data_size = read();
        clear = 1 << data_size;
        end_of_information = clear + 1;
        available = clear + 2;
        old_code = NullCode;
        code_size = data_size + 1;
        code_mask = (1 << code_size) - 1;
        for (code = 0; code < clear; code++) {
            prefix[code] = 0;
            suffix[code] = (byte) code;
        }

        // Decode GIF pixel stream.
        datum = bits = count = first = top = pi = bi = 0;
        for (i = 0; i < npix;) {
            if (top == 0) {
                if (bits < code_size) {
                    // Load bytes until there are enough bits for a code.
                    if (count == 0) {
                        // Read a new data block.
                        count = readBlock();
                        if (count <= 0) {
                            break;
                        }
                        bi = 0;
                    }
                    datum += (((int) block[bi]) & 0xff) << bits;
                    bits += 8;
                    bi++;
                    count--;
                    continue;
                }
                // Get the next code.
                code = datum & code_mask;
                datum >>= code_size;
                bits -= code_size;

                // Interpret the code
                if ((code > available) || (code == end_of_information)) {
                    break;
                }
                if (code == clear) {
                    // Reset decoder.
                    code_size = data_size + 1;
                    code_mask = (1 << code_size) - 1;
                    available = clear + 2;
                    old_code = NullCode;
                    continue;
                }
                if (old_code == NullCode) {
                    pixelStack[top++] = suffix[code];
                    old_code = code;
                    first = code;
                    continue;
                }
                in_code = code;
                if (code == available) {
                    pixelStack[top++] = (byte) first;
                    code = old_code;
                }
                while (code > clear) {
                    pixelStack[top++] = suffix[code];
                    code = prefix[code];
                }
                first = ((int) suffix[code]) & 0xff;
                // Add a new string to the string table,
                if (available >= MaxStackSize) {
                    break;
                }
                pixelStack[top++] = (byte) first;
                prefix[available] = (short) old_code;
                suffix[available] = (byte) first;
                available++;
                if (((available & code_mask) == 0)
                        && (available < MaxStackSize)) {
                    code_size++;
                    code_mask += available;
                }
                old_code = in_code;
            }

            // Pop a pixel off the pixel stack.
            top--;
            pixels[pi++] = pixelStack[top];
            i++;
        }
        for (i = pi; i < npix; i++) {
            pixels[i] = 0; // clear missing pixels
        }
    }

    /**
     * 判断当前解码过程是否出错，若出错返回true
     * @return
     */
    protected boolean err() {
        return status != STATUS_OK;
    }

    /**
     * 初始化参数
     */
    protected void init() {
        status = STATUS_OK;
        frameCount = 0;
        frames = new Vector<GifFrame>();
        gct = null;
        lct = null;
    }

    /**
     * 按顺序一个一个读取输入流字节，失败则设置读取失败状态码
     * @return
     */
    protected int read() {
        int curByte = 0;
        try {
            curByte = in.read();
        } catch (Exception e) {
            status = STATUS_FORMAT_ERROR;
        }
        return curByte;
    }

    /**
     * 读取扩展块(应用程序扩展块)
     * @return
     */
    protected int readBlock() {
        blockSize = read();
        int n = 0;
        if (blockSize > 0) {
            try {
                int count = 0;
                while (n < blockSize) {
                    count = in.read(block, n, blockSize - n);
                    if (count == -1) {
                        break;
                    }
                    n += count;
                }
            } catch (Exception e) {
                e.printStackTrace();
            }
            if (n < blockSize) {
                status = STATUS_FORMAT_ERROR;
            }
        }
        return n;
    }

    /**
     * 读取颜色列表
     * @param ncolors 列表大小，即颜色数量
     * @return
     */
    protected int[] readColorTable(int ncolors) {
        int nbytes = 3 * ncolors;//一个颜色占3个字节（r g b 各占1字节），因此占用空间为 颜色数量*3 字节
        int[] tab = null;
        byte[] c = new byte[nbytes];
        int n = 0;
        try {
            n = in.read(c);
        } catch (Exception e) {
            e.printStackTrace();
        }
        if (n < nbytes) {
            status = STATUS_FORMAT_ERROR;
        } else {//开始解析颜色列表
            tab = new int[256];//设置最大尺寸避免边界检查
            int i = 0;
            int j = 0;
            while (i < ncolors) {
                int r = ((int) c[j++]) & 0xff;
                int g = ((int) c[j++]) & 0xff;
                int b = ((int) c[j++]) & 0xff;
                tab[i++] = 0xff000000 | (r << 16) | (g << 8) | b;
            }
        }
        return tab;
    }

    /**
     * 读取图像块内容
     */
    protected void readContents() {
        boolean done = false;
        while (!(done || err())) {
            int code = read();
            switch (code) {
                //图象标识符(Image Descriptor)开始
                case 0x2C:
                    readImage();
                    break;
                //扩展块开始
                case 0x21: //扩展块标识，固定值0x21
                    code = read();
                    switch (code) {
                        case 0xf9: //图形控制扩展块标识(Graphic Control Label)，固定值0xf9
                            readGraphicControlExt();
                            break;

                        case 0xff: //应用程序扩展块标识(Application Extension Label)，固定值0xFF
                            readBlock();
                            String app = "";
                            for (int i = 0; i < 11; i++) {
                                app += (char) block[i];
                            }
                            if (app.equals("NETSCAPE2.0")) {
                                readNetscapeExt();
                            } else {
                                skip(); // don't care
                            }
                            break;
                        default: //其他扩展都选择跳过
                            skip();
                    }
                    break;

                case 0x3b://标识GIF文件结束，固定值0x3B
                    done = true;
                    break;

                case 0x00: //可能会出现的坏字节，可根据需要在此处编写坏字节分析等相关内容
                    break;
                default:
                    status = STATUS_FORMAT_ERROR;
            }
        }
    }

    /**
     * 读取图形控制扩展块
     */
    protected void readGraphicControlExt() {
        read();//按读取顺序，此处为块大小

        int packed = read();//读取处置方法、用户输入标志等
        dispose = (packed & 0x1c) >> 2; //从packed中解析出处置方法(Disposal Method)
        if (dispose == 0) {
            dispose = 1; //elect to keep old image if discretionary
        }
        transparency = (packed & 1) != 0;//从packed中解析出透明色标志

        delay = readShort() * 10;//读取延迟时间(毫秒)
        transIndex = read();//读取透明色索引
        read();//按读取顺序，此处为标识块终结(Block Terminator)
    }

    /**
     * 读取GIF 文件头、逻辑屏幕标识符、全局颜色列表
     */
    protected void readHeader() {
        //根据文件头判断是否GIF图片
        String id = "";
        for (int i = 0; i < 6; i++) {
            id += (char) read();
        }
        if (!id.toUpperCase().startsWith("GIF")) {
            status = STATUS_FORMAT_ERROR;
            return;
        }

        //解析GIF逻辑屏幕标识符
        readLSD();

        //读取全局颜色列表
        if (gctFlag && !err()) {
            gct = readColorTable(gctSize);
            bgColor = gct[bgIndex];//根据索引在全局颜色列表拿到背景颜色
        }
    }

    /**
     * 按顺序读取图像块数据：
     * 图象标识符(Image Descriptor)
     * 局部颜色列表(Local Color Table)（有的话）
     * 基于颜色列表的图象数据(Table-Based Image Data)
     */
    protected void readImage() {
        /**
         * 开始读取图象标识符(Image Descriptor)
         */
        ix = readShort();//x方向偏移量
        iy = readShort();//y方向偏移量
        iw = readShort();//图像宽度
        ih = readShort();//图像高度

        int packed = read();
        lctFlag = (packed & 0x80) != 0;//局部颜色列表标志(Local Color Table Flag)
        interlace = (packed & 0x40) != 0;//交织标志(Interlace Flag)
        // 3 - sort flag
        // 4-5 - reserved
        lctSize = 2 << (packed & 7);//局部颜色列表大小(Size of Local Color Table)

        /**
         * 开始读取局部颜色列表(Local Color Table)
         */
        if (lctFlag) {
            lct = readColorTable(lctSize);//解码局部颜色列表
            act = lct;//若有局部颜色列表，则图象数据是基于局部颜色列表的
        } else {
            act = gct; //否则都以全局颜色列表为准
            if (bgIndex == transIndex) {
                bgColor = 0;
            }
        }
        int save = 0;
        if (transparency) {
            save = act[transIndex];//保存透明色索引位置原来的颜色
            act[transIndex] = 0;//根据索引位置设置透明颜色
        }
        if (act == null) {
            status = STATUS_FORMAT_ERROR;//若没有颜色列表可用，则解码出错
        }
        if (err()) {
            return;
        }

        /**
         * 开始解码图像数据
         */
        decodeImageData();
        skip();
        if (err()) {
            return;
        }
        frameCount++;
        image = Bitmap.createBitmap(width, height, Bitmap.Config.RGB_565);
        setPixels(); //将像素数据转换为图像Bitmap
        frames.addElement(new GifFrame(image, delay));//添加到帧图集合
        // list
        if (transparency) {
            act[transIndex] = save;//重置回原来的颜色
        }
        resetFrame();
    }

    /**
     * 读取逻辑屏幕标识符(Logical Screen Descriptor)与全局颜色列表(Global Color Table)
     */
    protected void readLSD() {
        //获取GIF图像宽高
        width = readShort();
        height = readShort();

        /**
         * 解析全局颜色列表(Global Color Table)的配置信息
         * 配置信息占一个字节，具体各Bit存放的数据如下
         *    7   6 5 4   3   2 1 0	 BIT
         *  | m |   cr  | s | pixel |
         */
        int packed = read();
        gctFlag = (packed & 0x80) != 0;//判断是否有全局颜色列表（m,0x80在计算机内部表示为1000 0000）
        gctSize = 2 << (packed & 7);//读取全局颜色列表大小（pixel）

        //读取背景颜色索引和像素宽高比(Pixel Aspect Radio)
        bgIndex = read();
        pixelAspect = read();
    }

    protected void readNetscapeExt() {
        do {
            readBlock();
            if (block[0] == 1) {
                // loop count sub-block
                int b1 = ((int) block[1]) & 0xff;
                int b2 = ((int) block[2]) & 0xff;
                loopCount = (b2 << 8) | b1;
            }
        } while ((blockSize > 0) && !err());
    }

    /**
     * 读取两个字节的数据
     * @return
     */
    protected int readShort() {
        return read() | (read() << 8);
    }

    protected void resetFrame() {
        lastDispose = dispose;
        lrx = ix;
        lry = iy;
        lrw = iw;
        lrh = ih;
        lastImage = image;
        lastBgColor = bgColor;
        dispose = 0;
        transparency = false;
        delay = 0;
        lct = null;
    }

    /**
     * Skips variable length blocks up to and including next zero length block.
     */
    protected void skip() {
        do {
            readBlock();
        } while ((blockSize > 0) && !err());
    }
}
