(ns onkalo.util.image-resize
  (:require [clojure.java.io :as io]
            [taoensso.timbre :as timbre])
  (:import [java.awt.image BufferedImage]
           [java.io ByteArrayOutputStream InputStream]
           [javax.imageio ImageIO]
           [java.awt RenderingHints]))

(defn- create-scaled-image [^BufferedImage src-image w h]
  (let [scaled-image (BufferedImage. w h BufferedImage/TYPE_INT_RGB)]
    (doto (.createGraphics scaled-image)
      (.setRenderingHint RenderingHints/KEY_INTERPOLATION RenderingHints/VALUE_INTERPOLATION_BILINEAR)
      (.clearRect 0 0 w h)
      (.drawImage src-image 0 0 w h nil)
      (.dispose))
    scaled-image))

(defn- ^BufferedImage scale-to-max-dimension
  "Scales image so that the longer side is max `max-dimension` pixels, keeping ratio intact."
  [^BufferedImage buffered-image max-dimension]
  (let [width (.getWidth buffered-image)
        height (.getHeight buffered-image)
        ratio (/ width height)]
    (cond
      (and (<= width max-dimension) (<= height max-dimension))
      buffered-image

      (and (> width max-dimension)
           (> width height))
      (create-scaled-image buffered-image max-dimension (/ max-dimension ratio))

      :else
      (create-scaled-image buffered-image (* max-dimension ratio) max-dimension))))

(defn ^InputStream scale
  "Reads an image from the provided input stream and returns an input stream containing the image
   scaled so that the longer side is `max-dimension` pixels. If the image is smaller, the original image
   is returned. Consumes and closes the provided input stream."
  [^InputStream input max-dimension]
  (try
    (with-open [is         input
                out-stream (ByteArrayOutputStream.)]
      (if (-> (ImageIO/read is)
              (scale-to-max-dimension max-dimension)
              (ImageIO/write "jpeg" out-stream))
        (io/input-stream (.toByteArray out-stream))
        (throw (Exception. "No appropriate writer found for ImageIO"))))
    (catch Exception e
      (timbre/error e "Could not scale image")
      (throw e))))
