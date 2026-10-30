import type {ActivityPage} from "@/api/contract/activity"

export interface ActivityRepository {
    /** The newest activity, or the page before `cursor`. */
    page(cursor?: string): Promise<ActivityPage>
}
