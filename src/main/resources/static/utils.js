async function getImageSizeFromArrayBuffer(arrayBuffer)
{
    const blob = new Blob([arrayBuffer]);
    const bitmap = await createImageBitmap(blob);
    const size = { width: bitmap.width, height: bitmap.height };
    bitmap.close?.();
    return size;
}

function isMobileDevice()
{
    return /Android|webOS|iPhone|iPad|iPod|BlackBerry|IEMobile|Opera Mini/i.test(navigator.userAgent);
}