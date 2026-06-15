file(REMOVE_RECURSE
  "CMakeFiles/tokenizers-external"
  "CMakeFiles/tokenizers-external-complete"
  "external/src/tokenizers-external-stamp/tokenizers-external-build"
  "external/src/tokenizers-external-stamp/tokenizers-external-configure"
  "external/src/tokenizers-external-stamp/tokenizers-external-download"
  "external/src/tokenizers-external-stamp/tokenizers-external-install"
  "external/src/tokenizers-external-stamp/tokenizers-external-mkdir"
  "external/src/tokenizers-external-stamp/tokenizers-external-patch"
  "external/src/tokenizers-external-stamp/tokenizers-external-update"
)

# Per-language clean rules from dependency scanning.
foreach(lang )
  include(CMakeFiles/tokenizers-external.dir/cmake_clean_${lang}.cmake OPTIONAL)
endforeach()
