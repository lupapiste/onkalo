(ns onkalo.boundary.message-queue)

(defprotocol MessageQueue
  (publish [this topic-name message]))
