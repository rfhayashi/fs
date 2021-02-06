(ns babashka.fs-test
  (:require #_[me.raynes.fs :as rfs]
            [babashka.fs :as fs]
            [clojure.java.io :as io]
            [clojure.set :as set]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]))

(def cwd (fs/real-path "."))

(defn temp-dir []
  (-> (fs/create-temp-dir)
      (fs/delete-on-exit)))

(deftest walk-test
  (let [dir-counter (volatile! 0)
        file-counter (volatile! 0)]
    (fs/walk-file-tree "." {:post-visit-dir (fn [_ _] (vswap! dir-counter inc) :continue)
                            :visit-file (fn [_ _] (vswap! file-counter inc) :continue)
                            :max-depth 2})
    (is (pos? @dir-counter))
    (is (pos? @file-counter)))
  (testing "max-depth 0"
    (let [dir-counter (volatile! 0)
          file-counter (volatile! 0)]
      (fs/walk-file-tree "." {:post-visit-dir (fn [_ _] (vswap! dir-counter inc) :continue)
                              :visit-file (fn [_ _] (vswap! file-counter inc) :continue)
                              :max-depth 0})
      (is (zero? @dir-counter))
      (is (= 1 @file-counter))))
  (is (fs/walk-file-tree "." {:pre-visit-dir (fn [_ _] :terminate)}))
  (is (fs/walk-file-tree "." {:pre-visit-dir (fn [_ _] java.nio.file.FileVisitResult/TERMINATE)}))
  (is (thrown-with-msg?
       Exception #":continue, :skip-subtree, :skip-siblings, :terminate"
       (fs/walk-file-tree "." {:pre-visit-dir (fn [_ _])}))))

(deftest glob-test
  (is (= '("README.md") (map str
                             (fs/glob "." "README.md"))))
  (is (set/subset? #{"project.clj"
                     "test/babashka/fs_test.clj"
                     "src/babashka/fs.cljc"}
                   (set (map str
                             (fs/glob "." "**.{clj,cljc}")))))
  (testing "glob also matches directories and doesn't return the root directory"
    (is (= '("test-resources/foo/1" "test-resources/foo/foo")
           (map str
                (fs/glob "test-resources/foo" "**"))))
    (is (= '("test-resources/foo/1" "test-resources/foo/foo")
           (map str
                (fs/glob "test-resources" "foo/**")))))
  (testing "symlink as root path"
    (let [tmp-dir1 (temp-dir)
          _ (spit (fs/file tmp-dir1 "dude.txt") "contents")
          tmp-dir2 (temp-dir)
          sym-link (fs/create-sym-link (fs/file tmp-dir2 "sym-link") tmp-dir1)]
      (is (empty? (fs/glob sym-link "**")))
      (is (= 1 (count (fs/glob sym-link "**" {:follow-links true}))))
      (is (= 1 (count (fs/glob (fs/real-path sym-link) "**"))))))
  (testing "glob with specific depth"
    (let [tmp-dir1 (temp-dir)
          nested-dir (fs/file tmp-dir1 "foo" "bar" "baz")
          _ (fs/create-dirs nested-dir)
          _ (spit (fs/file nested-dir "dude.txt") "contents")]
      (is (= 1 (count (fs/glob tmp-dir1 "foo/bar/baz/*")))))))

(deftest create-dir-test
  (is (fs/create-dir (fs/path (temp-dir) "foo"))))

(deftest parent-test
  (let [tmp-dir (temp-dir)]
    (is (-> (fs/create-dir (fs/path tmp-dir "foo"))
            fs/parent
            (= tmp-dir)))))

(deftest file-name-test
  (is (= "fs" (fs/file-name cwd)))
  (is (= "fs" (fs/file-name (fs/file cwd))))
  (is (= "fs" (fs/file-name (fs/path cwd)))))

(deftest path-test
  (let [p (fs/path "foo" "bar" (io/file "baz"))]
    (is (instance? java.nio.file.Path p))
    (is (= "foo/bar/baz" (str p)))))

(deftest file-test
  (let [f (fs/file "foo" "bar" (fs/path "baz"))]
    (is (instance? java.io.File f))
    (is (= "foo/bar/baz" (str f)))))

(deftest copy-test
  (let [tmp-dir (temp-dir)
        tmp-file (fs/create-file (fs/path tmp-dir "tmp-file"))
        dest-path (fs/path tmp-dir "tmp-file-dest")]
    (fs/copy tmp-file dest-path)
    (is (fs/exists? dest-path))))

(deftest copy-tree-test
  (let [tmp-dir (temp-dir)]
    (fs/copy-tree "." tmp-dir)
    (let [cur-dir-count (count (fs/glob "." "**" #{:hidden}))
          tmp-dir-count (count (fs/glob tmp-dir "**" #{:hidden}))]
      (is (pos? cur-dir-count))
      (is (= cur-dir-count tmp-dir-count)))))

(deftest components-test
  (let [paths (map str (fs/components (fs/real-path ".")))]
    (is (= "fs" (last paths)))
    (is (> (count paths) 1))))

(deftest list-dir-test
  (let [paths (map str (fs/list-dir (fs/real-path ".")))]
    (is (> (count paths) 1)))
  (let [paths (map str (fs/list-dir (fs/real-path ".") (fn accept [x] (fs/directory? x))))]
    (is (> (count paths) 1)))
  (let [paths (map str (fs/list-dir (fs/real-path ".") (fn accept [_] false)))]
    (is (zero? (count paths))))
  (let [paths (map str (fs/list-dir (fs/real-path ".") "*.clj"))]
    (is (pos? (count paths)))))

(deftest delete-tree-test
  (let [tmp-dir1 (temp-dir)
        nested-dir (fs/file tmp-dir1 "foo" "bar" "baz")
        tmp-file (fs/file nested-dir "tmp-file")
        _ (fs/create-dirs nested-dir)]
    (is (fs/exists? nested-dir))
    (fs/create-file (fs/file nested-dir "tmp-file"))
    (is (fs/exists? tmp-file))
    (fs/delete-tree nested-dir)
    (is (not (fs/exists? nested-dir)))))

(deftest move-test
  (let [tmp-dir1 (fs/create-temp-dir)
        f (fs/file tmp-dir1 "foo.txt")
        _ (spit f "foo")
        f2 (fs/file tmp-dir1 "bar.txt")]
    (fs/move f f2)
    (is (not (fs/exists? f)))
    (is (fs/exists? f2))
    (is (= "foo" (str/trim (slurp f2))))))

(deftest set-attribute-test
  (let [dir (fs/create-temp-dir)
        tmp-file (fs/create-file (fs/path dir "tmp-file"))]
    (is (= 100 (-> (fs/set-attribute tmp-file "basic:lastModifiedTime" (fs/millis->file-time 100))
                   (fs/read-attributes "*") :lastModifiedTime fs/file-time->millis)))))

(deftest list-dirs-and-which-test
  (let [java (first (filter fs/executable?
                            (fs/list-dirs
                             (filter fs/exists?
                                     (fs/exec-path))
                             "java")))]
    (is java)
    (is (= java (fs/which "java")))
    (is (contains? (set (fs/which "java" {:all true})) java))))

(deftest predicate-test
  (is (boolean? (fs/readable? (fs/path "."))))
  (is (boolean? (fs/writable? (fs/path ".")))))

(deftest normalize-test
  (is (not (str/includes? (fs/normalize (fs/absolutize ".")) "."))))

(deftest temp-dir-test
  (let [tmp-dir-in-temp-dir (fs/create-temp-dir {:path (fs/temp-dir)})]
    (is (fs/starts-with? tmp-dir-in-temp-dir (fs/temp-dir)))))

(deftest ends-with?-test
  (is (fs/ends-with? (fs/temp-dir) (last (fs/temp-dir)))))

(deftest posix-test
  (is (str/includes? (-> (fs/posix-file-permissions ".")
                         (fs/posix->str))
                     "rwx"))
  (is (= (fs/posix-file-permissions ".")
         (-> (fs/posix-file-permissions ".")
             (fs/posix->str)
             (fs/str->posix))))
  (is (= "rwx------"
         (-> (fs/create-temp-dir {:posix-file-permissions "rwx------"})
             (fs/posix-file-permissions)
             (fs/posix->str)))))

(deftest delete-if-exists-test
  (let [tmp-file (fs/create-file (fs/path (temp-dir) "dude"))]
    (is (true? (fs/delete-if-exists tmp-file)))
    (is (false? (fs/delete-if-exists tmp-file)))))

(deftest size-test
  (is (pos? (fs/size (fs/temp-dir)))))

(deftest set-posix-test
  (let [tmp-file (fs/create-file (fs/path (temp-dir) "foo"))]
    (is (fs/set-posix-file-permissions tmp-file
                                       "rwx------"))
    (is (= "rwx------"
           (fs/posix->str (fs/posix-file-permissions tmp-file))))))

(deftest same-file?
  (fs/same-file? (fs/path ".") (fs/real-path ".")))

(deftest read-all-bytes-test
  (let [bs (fs/read-all-bytes "README.md")]
    (is (bytes? bs))
    (is (= (fs/size "README.md") (count bs)))))

(deftest read-all-lines-test
  (let [ls (fs/read-all-lines "README.md")]
    (is (= ls (line-seq (io/reader (fs/file "README.md")))))))

(deftest get-attribute-test
  (let [lmt (fs/get-attribute "." "basic:lastModifiedTime")]
    (is lmt)
    (is (= lmt (fs/last-modified-time ".")))))
