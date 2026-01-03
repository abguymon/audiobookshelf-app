<template>
  <modals-modal v-model="show" :width="400" height="100%">
    <template #outer>
      <div class="absolute top-11 left-4 z-40">
        <p class="text-white text-2xl truncate">{{ $strings.LabelYourBookmarks }}</p>
      </div>
    </template>
    <div class="w-full h-full overflow-hidden absolute top-0 left-0 flex items-center justify-center" @click="show = false">
      <div class="w-full rounded-lg bg-primary border border-border overflow-y-auto overflow-x-hidden relative mt-16" style="max-height: 80vh" @click.stop.prevent>
        <div class="w-full h-full p-4" v-if="showBookmarkTitleInput">
          <div class="flex mb-4 items-center">
            <div class="w-9 h-9 flex items-center justify-center rounded-full hover:bg-white hover:bg-opacity-10 cursor-pointer" @click.stop="showBookmarkTitleInput = false">
              <span class="material-symbols text-3xl">arrow_back</span>
            </div>
            <p class="text-xl pl-2">{{ selectedBookmark ? 'Edit Bookmark' : 'New Bookmark' }}</p>
            <div class="flex-grow" />
            <p class="text-xl font-mono">{{ this.$secondsToTimestamp(currentTime / _playbackRate) }}</p>
          </div>

          <ui-text-input-with-label v-model="newBookmarkTitle" :placeholder="bookmarkPlaceholder()" :autofocus="false" ref="noteInput" label="Note" />
          <div class="flex justify-end mt-6">
            <ui-btn color="success" class="w-full" @click.stop="submitBookmark">{{ selectedBookmark ? 'Update' : 'Create' }}</ui-btn>
          </div>
        </div>
        <div class="w-full h-full" v-else>
          <!-- Multi-Select Header -->
          <div v-if="isMultiSelectMode" class="flex items-center justify-between mb-2 px-2 py-2 bg-bg bg-opacity-50 rounded-md">
            <div class="flex items-center">
              <span class="material-symbols text-2xl cursor-pointer mr-2" @click.stop="exitMultiSelectMode">close</span>
              <p class="text-lg">{{ selectedBookmarks.length }} Selected</p>
            </div>
            <div class="flex items-center">
              <span class="material-symbols text-2xl text-fg hover:text-error cursor-pointer" @click.stop="deleteSelectedBookmarks">delete</span>
            </div>
          </div>

          <template v-for="bookmark in bookmarks">
            <modals-bookmarks-bookmark-item
              :key="bookmark.time"
              :highlight="currentTime === bookmark.time"
              :bookmark="bookmark"
              :playback-rate="_playbackRate"
              :selected="selectedBookmarkIds.has(bookmark.time)"
              :multi-select-mode="isMultiSelectMode"
              @click="clickBookmark"
              @edit="editBookmark"
              @delete="deleteBookmark"
              @longpress="handleLongPress"
            />
          </template>
          <div v-if="!bookmarks.length" class="flex h-32 items-center justify-center">
            <p class="text-xl">{{ $strings.MessageNoBookmarks }}</p>
          </div>
        </div>
        <div v-if="canCreateBookmark && !showBookmarkTitleInput && !isMultiSelectMode" class="flex px-4 py-2 items-center text-center justify-between border-b border-fg/10 bg-success cursor-pointer text-white text-opacity-80 sticky bottom-0 left-0 w-full" @click.stop="createBookmark">
          <span class="material-symbols">add</span>
          <p class="text-base pl-2">{{ $strings.ButtonCreateBookmark }}</p>
          <p class="text-sm font-mono">{{ this.$secondsToTimestamp(currentTime / _playbackRate) }}</p>
        </div>
      </div>
    </div>
  </modals-modal>
</template>

<script>
import { Dialog } from '@capacitor/dialog'

export default {
  props: {
    value: Boolean,
    bookmarks: {
      type: Array,
      default: () => []
    },
    currentTime: {
      type: Number,
      default: 0
    },
    playbackRate: {
      type: Number,
      default: 1
    },
    libraryItemId: String
  },
  data() {
    return {
      selectedBookmark: null,
      showBookmarkTitleInput: false,
      newBookmarkTitle: '',
      isMultiSelectMode: false,
      selectedBookmarks: [] // Array of bookmark times (unique IDs)
    }
  },
  watch: {
    show(newVal) {
      if (newVal) {
        this.showBookmarkTitleInput = false
        this.newBookmarkTitle = ''
        this.exitMultiSelectMode()
      }
    }
  },
  computed: {
    show: {
      get() {
        return this.value
      },
      set(val) {
        this.$emit('input', val)
      }
    },
    canCreateBookmark() {
      return !this.bookmarks.find((bm) => bm.time === this.currentTime)
    },
    _playbackRate() {
      if (!this.playbackRate || isNaN(this.playbackRate)) return 1
      return this.playbackRate
    },
    selectedBookmarkIds() {
      return new Set(this.selectedBookmarks)
    }
  },
  methods: {
    bookmarkPlaceholder() {
      // using a method prevents caching the date
      return this.$formatDate(Date.now(), 'MMM dd, yyyy HH:mm')
    },
    async handleLongPress(bm) {
      await this.$hapticsImpact()
      if (!this.isMultiSelectMode) {
        this.isMultiSelectMode = true
        this.selectedBookmarks = [bm.time]
      } else {
        // Already in multi-select, toggle
        this.toggleSelection(bm)
      }
    },
    exitMultiSelectMode() {
      this.isMultiSelectMode = false
      this.selectedBookmarks = []
    },
    toggleSelection(bm) {
      const index = this.selectedBookmarks.indexOf(bm.time)
      if (index === -1) {
        this.selectedBookmarks.push(bm.time)
      } else {
        this.selectedBookmarks.splice(index, 1)
        if (this.selectedBookmarks.length === 0) {
          this.exitMultiSelectMode()
        }
      }
    },
    editBookmark(bm) {
      if (this.isMultiSelectMode) return
      this.selectedBookmark = bm
      this.newBookmarkTitle = bm.title
      this.showBookmarkTitleInput = true
    },
    async deleteSelectedBookmarks() {
      if (!this.selectedBookmarks.length) return
      await this.$hapticsImpact()

      const { value } = await Dialog.confirm({
        title: 'Remove Bookmarks',
        message: `Are you sure you want to remove ${this.selectedBookmarks.length} bookmarks?`
      })
      if (!value) return

      // Since the API seems to be single delete, we iterate.
      // Ideally we should have a bulk delete endpoint.
      // We will perform deletions in parallel.
      const promises = this.selectedBookmarks.map(time => {
        return this.$nativeHttp
          .delete(`/api/me/item/${this.libraryItemId}/bookmark/${time}`)
          .then(() => {
             this.$store.commit('user/deleteBookmark', { libraryItemId: this.libraryItemId, time: time })
          })
          .catch(err => {
            console.error('Failed to delete bookmark', time, err)
            // We continue even if one fails
          })
      })

      try {
        await Promise.all(promises)
        this.$toast.success('Bookmarks removed')
      } catch (error) {
        this.$toast.error(this.$strings.ToastBookmarkRemoveFailed)
      } finally {
        this.exitMultiSelectMode()
      }
    },
    async deleteBookmark(bm) {
      await this.$hapticsImpact()
      const { value } = await Dialog.confirm({
        title: 'Remove Bookmark',
        message: this.$strings.MessageConfirmRemoveBookmark
      })
      if (!value) return

      this.$nativeHttp
        .delete(`/api/me/item/${this.libraryItemId}/bookmark/${bm.time}`)
        .then(() => {
          this.$store.commit('user/deleteBookmark', { libraryItemId: this.libraryItemId, time: bm.time })
        })
        .catch((error) => {
          this.$toast.error(this.$strings.ToastBookmarkRemoveFailed)
          console.error(error)
        })
    },
    async clickBookmark(bm) {
      if (this.isMultiSelectMode) {
        this.toggleSelection(bm)
      } else {
        await this.$hapticsImpact()
        this.$emit('select', bm)
      }
    },
    submitUpdateBookmark(updatedBookmark) {
      this.$nativeHttp
        .patch(`/api/me/item/${this.libraryItemId}/bookmark`, updatedBookmark)
        .then((bookmark) => {
          this.$store.commit('user/updateBookmark', bookmark)
          this.showBookmarkTitleInput = false
        })
        .catch((error) => {
          this.$toast.error(this.$strings.ToastBookmarkUpdateFailed)
          console.error(error)
        })
    },
    submitCreateBookmark() {
      if (!this.newBookmarkTitle) {
        this.newBookmarkTitle = this.$formatDate(Date.now(), 'MMM dd, yyyy HH:mm')
      }
      const bookmark = {
        title: this.newBookmarkTitle,
        time: Math.floor(this.currentTime)
      }
      this.$nativeHttp
        .post(`/api/me/item/${this.libraryItemId}/bookmark`, bookmark)
        .then(() => {
          this.$toast.success('Bookmark added')
        })
        .catch((error) => {
          this.$toast.error(this.$strings.ToastBookmarkCreateFailed)
          console.error(error)
        })

      this.newBookmarkTitle = ''
      this.showBookmarkTitleInput = false

      this.show = false
    },
    createBookmark() {
      this.selectedBookmark = null
      this.newBookmarkTitle = ''
      this.showBookmarkTitleInput = true
    },
    async submitBookmark() {
      await this.$hapticsImpact()
      if (this.selectedBookmark) {
        var updatePayload = {
          ...this.selectedBookmark,
          title: this.newBookmarkTitle
        }
        this.submitUpdateBookmark(updatePayload)
      } else {
        this.submitCreateBookmark()
      }
    }
  },
  mounted() {}
}
</script>
