(ns onkalo.util.lupapiste-api-test
  (:require [clojure.test :refer :all]
            [onkalo.util.lupapiste-api :as la]))

(deftest allowed-document-types-search-parameter
  (with-redefs
    [la/allowed-terminal-document-types-for-organization (fn [_ org-id]
                                                           (case org-id
                                                             "123-R" ["foo.bar" "all.right"]
                                                             "313-R" []
                                                             nil))
     la/allowed-departmental-document-types-for-organization  (fn [_ org-id]
                                                                (case org-id
                                                                  "123-R" []
                                                                  "313-R" ["well.done"]
                                                                  nil))]
    (testing "terminal"
      (is (= {:allowed-document-types [["123-R" ["foo.bar" "all.right"]]
                                       ["001-YA" []]
                                       ["313-R" []]]}
             (la/allowed-document-types-search-parameter :api :terminal
                                                         ["123-R" "001-YA" "313-R"])))
      (is (= {:allowed-document-types []}
             (la/allowed-document-types-search-parameter :api :terminal []))))
    (testing "departmental"
      (is (= {:allowed-document-types [["123-R" []]
                                       ["001-YA" []]
                                       ["313-R" ["well.done"]]]}
             (la/allowed-document-types-search-parameter :api :departmental
                                                         ["123-R" "001-YA" "313-R"])))
      (is (= {:allowed-document-types []}
             (la/allowed-document-types-search-parameter :api :departmental []))))
    (testing "bad instance-type"
      (is (thrown? IllegalArgumentException
                   (la/allowed-document-types-search-parameter :api :bad []))))))
