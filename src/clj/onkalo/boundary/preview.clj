(ns onkalo.boundary.preview)

(defprotocol PreviewGenerator
  (generate-preview [this storage organization id]))
